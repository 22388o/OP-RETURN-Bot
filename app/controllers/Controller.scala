package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import com.google.protobuf.ByteString
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.LnTag.PaymentHashTag
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.control.OP_RETURN
import org.bitcoins.core.util.{BitcoinScriptUtil, FutureUtil}
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{DoubleSha256DigestBE, Sha256Digest}
import org.bitcoins.feeprovider._
import org.bitcoins.feeprovider.MempoolSpaceTarget._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.LndUtils._
import play.api.data._
import play.api.mvc._
import scodec.bits.ByteVector
import signrpc.TxOut
import walletrpc.SendOutputsRequest

import javax.inject.Inject
import scala.collection._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Controller @Inject() (cc: MessagesControllerComponents)
    extends MessagesAbstractController(cc)
    with TwitterHandler
    with OnionMessageHandler
    with Logging {

  import controllers.Forms._

  implicit lazy val system: ActorSystem = {
    val system = ActorSystem("op-return-bot")
    system.log.info("Akka logger started")
    system
  }
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig.fromDefaultDatadir()

  val lnd: LndRpcClient = config.lndRpcClient

  val startF: Future[Unit] = config.start()

  if (config.startBinaries) {
    lnd.start()
  }

  val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, MainNet, None)

  val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  val uriErrorString = "Error: try again"
  var uri: String = uriErrorString

  def setURI(): Future[Unit] = {
    lnd.getInfo.map { info =>
      val torAddrOpt = info.uris.find(_.contains(".onion"))

      uri = torAddrOpt.getOrElse(info.uris.head)
    }
  }
  setURI()

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  // The URL to the request.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "Controller" references are inside the .scala file.
  private val postUrl = routes.Controller.createRequest()

  private val recentTransactions: ArrayBuffer[DoubleSha256DigestBE] = {
    val f = startF.flatMap(_ => invoiceDAO.lastFiveCompleted())
    val res = Await.result(f, 30.seconds)
    mutable.ArrayBuffer[DoubleSha256DigestBE]().addAll(res)
  }

  final val onionAddr =
    "http://opreturnqfd4qdv745xy6ncwvogbtxddttqkqkp5gipby6uytzpxwzqd.onion"

  private val telegramHandler = new TelegramHandler()

  telegramHandler.run()
  startSubscription()

  def index: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(
        views.html
          .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
        .withHeaders(("Onion-Location", onionAddr))
    }
  }

  def connect: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      if (uri == uriErrorString) {
        setURI().map { _ =>
          Ok(views.html.connect(uri)).withHeaders(("Onion-Location", onionAddr))
        }
      } else {
        Future.successful(
          Ok(views.html.connect(uri))
            .withHeaders(("Onion-Location", onionAddr)))
      }
    }
  }

  def viewMessage(txIdStr: String): Action[AnyContent] = {
    Action.async { _ =>
      val txId = DoubleSha256DigestBE(txIdStr)
      invoiceDAO.findByTxId(txId).map {
        case None =>
          BadRequest("Tx does not originate from OP_RETURN Bot")
        case Some(invoiceDb: InvoiceDb) =>
          Ok(invoiceDb.message)
      }
    }
  }

  def invoiceStatus(rHash: String): Action[AnyContent] = {
    Action.async { _ =>
      val hash = Sha256Digest.fromHex(rHash)
      invoiceDAO.read(hash).map {
        case None =>
          BadRequest("Invoice not from OP_RETURN Bot")
        case Some(invoiceDb) =>
          invoiceDb.txIdOpt match {
            case Some(txId) => Ok(txId.hex)
            case None       => BadRequest("Invoice has not been paid")
          }
      }
    }
  }

  def invoice(invoiceStr: String): Action[AnyContent] =
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      LnInvoice.fromStringT(invoiceStr) match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(invoice) =>
          invoiceDAO.read(invoice.lnTags.paymentHash.hash).map {
            case None =>
              BadRequest("Invoice not from OP_RETURN Bot")
            case Some(invoiceDb) =>
              invoiceDb.txIdOpt match {
                case Some(txId) => Redirect(routes.Controller.success(txId.hex))
                case None       => Ok(views.html.showInvoice(invoice))
              }
          }
      }
    }

  def success(txIdStr: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      Try(DoubleSha256DigestBE.fromHex(txIdStr)) match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(txId) =>
          invoiceDAO.findByTxId(txId).map {
            case None =>
              BadRequest(views.html
                .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
            case Some(invoiceDb) =>
              invoiceDb.txOpt match {
                case Some(tx) => Ok(views.html.success(tx))
                case None =>
                  throw new RuntimeException(
                    s"This is impossible, ${invoiceDb.invoice}")
              }
          }
      }
    }
  }

  def publishTransaction(txHex: String): Action[AnyContent] = {
    Try(Transaction.fromHex(txHex)) match {
      case Failure(exception) =>
        Action { implicit request: MessagesRequest[AnyContent] =>
          BadRequest(exception.getMessage)
        }
      case Success(tx) => publishTransaction(tx)
    }
  }

  def publishTransaction(tx: Transaction): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      lnd.publishTransaction(tx).map {
        case Some(error) => BadRequest(error)
        case None        => Ok(tx.txIdBE.hex)
      }
    }
  }

  def create: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      def failure(badForm: Form[OpReturnRequest]): Future[Result] = {
        Future.successful(BadRequest(badForm.errorsAsJson))
      }

      def success(input: OpReturnRequest): Future[Result] = {
        for {
          invoiceDb <- processMessage(input.message, input.noTwitter, None)
        } yield {
          Ok(invoiceDb.invoice.toString())
        }
      }

      opReturnRequestForm.bindFromRequest().fold(failure, success)
    }
  }

  // This will be the action that handles our form post
  def createRequest: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val errorFunction: Form[OpReturnRequest] => Future[Result] = {
        formWithErrors: Form[OpReturnRequest] =>
          // This is the bad case, where the form had validation errors.
          // Let's show the user the form again, with the errors highlighted.
          // Note how we pass the form with errors to the template.
          logger.warn(
            "From with errors: " + formWithErrors.errors.mkString(
              " ") + s"\n${formWithErrors.data}")

          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, formWithErrors, postUrl)))
      }

      // This is the good case, where the form was successfully parsed as an OpReturnRequest
      val successFunction: OpReturnRequest => Future[Result] = {
        data: OpReturnRequest =>
          processMessage(data.message, data.noTwitter, None).map { invoiceDb =>
            Redirect(routes.Controller.invoice(invoiceDb.invoice.toString()))
          }
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }

  protected def processMessage(
      message: String,
      noTwitter: Boolean,
      nodeIdOpt: Option[NodeId]): Future[InvoiceDb] = {
    require(
      message.getBytes.length <= 80,
      "OP_Return message received was too long, must be less than 80 chars")

    fetchFeeRate()
      .flatMap { rate: SatoshisPerVirtualByte =>
        // add 4 so we get better odds of getting in next block
        val feeRate = rate.copy(rate.currencyUnit + Satoshis(4))

        // 125 base tx fee * 2 just in case
        val baseSize = 250
        val messageSize = message.getBytes.length

        // Add fee if no tweet
        val noTwitterFee = if (noTwitter) Satoshis(1000) else Satoshis.zero

        // tx fee + app fee (1337) + twitter fee
        val sats =
          (feeRate * (baseSize + messageSize)) + Satoshis(1337) + noTwitterFee
        val expiry = 60 * 5 // 5 minutes

        lnd
          .addInvoice(s"OP_RETURN Bot: $message", sats.satoshis, expiry)
          .flatMap { invoiceResult =>
            val invoice = invoiceResult.invoice
            val db: InvoiceDb =
              InvoiceDb(
                rHash = invoiceResult.rHash.hash,
                invoice = invoice,
                message = message,
                noTwitter = noTwitter,
                feeRate = feeRate,
                nodeIdOpt = nodeIdOpt,
                txOpt = None,
                txIdOpt = None,
                profitOpt = None,
                chainFeeOpt = None
              )
            invoiceDAO.create(db)
          }
      }
  }

  def startSubscription(): Future[Done] = {
    val parallelism = Runtime.getRuntime.availableProcessors()

    lnd
      .subscribeInvoices()
      .filter(_.state.isSettled)
      .mapAsync(parallelism) { invoice =>
        invoiceDAO.read(Sha256Digest(invoice.rHash)).map {
          case None =>
            logger.warn(
              s"Processed invoice not from OP_RETURN Bot, ${invoice.rHash.toHex}")
          case Some(invoiceDb) =>
            invoiceDb.txIdOpt match {
              case Some(_) =>
                logger.warn(
                  s"Processed invoice that already has a tx associated with it, rHash: ${invoice.rHash.toHex}")
              case None =>
                require(invoice.amtPaidMsat >= invoice.valueMsat,
                        "User did not pay invoice in full")
                onInvoicePaid(invoiceDb).map(_ => ())
            }
        }
      }
      .runWith(Sink.ignore)
  }

  private def onInvoicePaid(invoiceDb: InvoiceDb): Future[InvoiceDb] = {
    val message = invoiceDb.message
    val invoice = invoiceDb.invoice
    val feeRate = invoiceDb.feeRate
    val noTwitter = invoiceDb.noTwitter
    val rHash = PaymentHashTag(invoiceDb.rHash)

    logger.info(s"Received ${invoice.amount.get.toSatoshis}!")

    val output = {
      val messageBytes = ByteVector(message.getBytes)

      val asm = OP_RETURN +: BitcoinScriptUtil.calculatePushOp(
        messageBytes) :+ ScriptConstant(messageBytes)

      val scriptPubKey = ScriptPubKey(asm.toVector)

      TransactionOutput(Satoshis.zero, scriptPubKey)
    }

    val txOut =
      TxOut(output.value.satoshis.toLong,
            ByteString.copyFrom(output.scriptPubKey.asmBytes.toArray))

    val request: SendOutputsRequest = SendOutputsRequest(
      satPerKw = feeRate.toSatoshisPerKW.toLong,
      outputs = Vector(txOut),
      label = invoice.lnTags.description.map(_.string).getOrElse(""),
      spendUnconfirmed = true)

    val createTxF = for {
      transaction <- lnd.sendOutputs(request)
      errorOpt <- lnd.publishTransaction(transaction)

      // send if onion message
      _ <- invoiceDb.nodeIdOpt match {
        case Some(nodeId) =>
          // recover so we can finish accounting
          sendBroadcastTransactionTLV(nodeId, transaction).recover {
            case err: Throwable =>
              logger.error(
                s"Error sending onion message back to nodeId $nodeId",
                err)
          }
        case None => Future.unit
      }

      txId = transaction.txIdBE

      _ = errorOpt match {
        case Some(error) =>
          logger.error(
            s"Error when broadcasting transaction ${txId.hex}, $error")
        case None =>
          logger.info(s"Successfully created tx: ${txId.hex}")
      }

      txDetailsOpt <- lnd.getTransaction(txId)

      _ = {
        recentTransactions += txId
        if (recentTransactions.size >= 5) {
          val old = recentTransactions.takeRight(5)
          recentTransactions.clear()
          recentTransactions ++= old
        }
      }

      amount = invoice.amount.get.toSatoshis
      // Need to make sure we upsert the tx and txid even if this fails, so we can't call .get
      chainFeeOpt = txDetailsOpt.map(_.totalFees)
      profitOpt = txDetailsOpt.map(d => amount - d.totalFees)

      dbWithTx: InvoiceDb = invoiceDb.copy(txOpt = Some(transaction),
                                           txIdOpt = Some(txId),
                                           profitOpt = profitOpt,
                                           chainFeeOpt = chainFeeOpt)

      res <- invoiceDAO.upsert(dbWithTx)

      tweetOpt <-
        if (noTwitter) FutureUtil.none
        else
          handleTweet(message, txId).map(Some(_)).recover { err =>
            logger.error(
              s"Failed to create tweet for invoice ${rHash.hash.hex}, got error $err")
            None
          }
      _ <- {
        val telegramF = txDetailsOpt match {
          case Some(details) =>
            for {
              profit <- invoiceDAO.totalProfit()
              chainFees <- invoiceDAO.totalChainFees()
              _ <- telegramHandler.handleTelegram(rHash = rHash.hash,
                                                  invoice = invoice,
                                                  tweetOpt = tweetOpt,
                                                  message = message,
                                                  feeRate = feeRate,
                                                  txDetails = details,
                                                  totalProfit = profit,
                                                  totalChainFees = chainFees)
            } yield ()
          case None =>
            val msg =
              s"Failed to get transaction details for ${rHash.hash.hex}\n" +
                s"Transaction (${txId.hex}): ${transaction.hex}"
            logger.warn(msg)
            telegramHandler.sendTelegramMessage(msg)
        }

        telegramF.recover { err =>
          logger.error(
            s"Failed to send telegram message for invoice ${rHash.hash.hex}, got error $err")
        }
      }
    } yield res

    createTxF.failed.foreach { err =>
      logger.error(
        s"Failed to create tx for invoice ${rHash.hash.hex}, got error $err")
    }

    createTxF
  }

  private def fetchFeeRate(): Future[SatoshisPerVirtualByte] = {
    feeProvider.getFeeRate.recoverWith { case _: Throwable =>
      feeProviderBackup.getFeeRate
    }
  }
}
