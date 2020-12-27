package controllers

import akka.actor.ActorSystem
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.cli.{CliCommand, Config, ConsoleCli}
import org.bitcoins.commons.jsonmodels.eclair.IncomingPaymentStatus._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.DoubleSha256DigestBE
import org.bitcoins.eclair.rpc.client.EclairRpcClient
import play.api.data._
import play.api.mvc._

import javax.inject.Inject
import scala.collection._
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * The classic WidgetController using MessagesAbstractController.
  *
  * Instead of MessagesAbstractController, you can use the I18nSupport trait,
  * which provides implicits that create a Messages instance from a request
  * using implicit conversion.
  *
  * See https://www.playframework.com/documentation/2.8.x/ScalaForms#passing-messagesprovider-to-form-helpers
  * for details.
  */
class WidgetController @Inject() (cc: MessagesControllerComponents)
    extends MessagesAbstractController(cc)
    with Logging {
  import controllers.Forms._

  private val widgets = mutable.ArrayBuffer[OpReturnRequest]()

  implicit lazy val system: ActorSystem = {
    val system = ActorSystem("op-return-bot")
    system.log.info("Akka logger started")
    system
  }
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig.fromDefaultDatadir()

  config.start()

  val eclairBitcoindPair: EclairBitcoindPair = config.eclairBitcoindPair
  eclairBitcoindPair.start()

  val eclairRpc: EclairRpcClient = eclairBitcoindPair.eclair
  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  // The URL to the widget.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "WidgetController" references are inside the .scala file.
  private val postUrl = routes.WidgetController.createRequest()

  def index: Action[AnyContent] =
    Action {
      Ok(views.html.index())
    }

  def listWidgets: Action[AnyContent] =
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(views.html.listWidgets(widgets.toSeq, opReturnRequestForm, postUrl))
    }

  def invoice(invoiceStr: String): Action[AnyContent] =
    Action { implicit request: MessagesRequest[AnyContent] =>
      LnInvoice.fromStringT(invoiceStr) match {
        case Failure(exception) =>
          logger.error(exception)
          BadRequest(
            views.html.listWidgets(widgets.toSeq, opReturnRequestForm, postUrl))
        case Success(invoice) =>
          val resultF = invoiceDAO.read(invoice).map {
            case None =>
              Ok(views.html.showInvoice(invoice))
            case Some(InvoiceDb(_, tx, _)) =>
              Ok(views.html.success(tx))
          }

          Await.result(resultF, 30.seconds)
      }
    }

  def success(txIdStr: String): Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      Try(DoubleSha256DigestBE.fromHex(txIdStr)) match {
        case Failure(exception) =>
          logger.error(exception)
          BadRequest(
            views.html.listWidgets(widgets.toSeq, opReturnRequestForm, postUrl))
        case Success(txId) =>
          val resultF = invoiceDAO.findByTxId(txId).map {
            case None =>
              BadRequest(
                views.html
                  .listWidgets(widgets.toSeq, opReturnRequestForm, postUrl))
            case Some(InvoiceDb(_, tx, _)) =>
              Ok(views.html.success(tx))
          }

          Await.result(resultF, 30.seconds)
      }
    }
  }

  // This will be the action that handles our form post
  def createRequest: Action[AnyContent] =
    Action { implicit request: MessagesRequest[AnyContent] =>
      val errorFunction: Form[OpReturnRequest] => Result = {
        formWithErrors: Form[OpReturnRequest] =>
          // This is the bad case, where the form had validation errors.
          // Let's show the user the form again, with the errors highlighted.
          // Note how we pass the form with errors to the template.
          BadRequest(
            views.html.listWidgets(widgets.toSeq, formWithErrors, postUrl))
      }

      val successFunction: OpReturnRequest => Result = {
        data: OpReturnRequest =>
          val OpReturnRequest(message, hashMessage) = data
          require(
            message.length <= 80 || hashMessage,
            "OP_Return message received was too long, must be less than 80 chars, or hash the message")

          // 100 app fee + 102 base tx fee
          val baseSats = 100 + 102
          // if we are hashing the message it is a fixed 32 size
          val messageSats = if (hashMessage) 32 else message.length

          val sats = Satoshis(baseSats + messageSats)

          val result = eclairRpc
            .createInvoice(s"OP_RETURN Bot: $message",
                           MilliSatoshis(sats),
                           expireIn = 300.seconds)
            .map { invoice =>
              system.scheduler.scheduleOnce(5.seconds)(
                eclairRpc.monitorInvoice(invoice, 1.second, 300).flatMap { payment =>
                  payment.status match {
                    case Pending | Expired =>
                      FutureUtil.unit
                    case _: Received =>
                      ConsoleCli.exec(CliCommand.OpReturnCommit(
                                        message,
                                        hashMessage,
                                        Some(SatoshisPerVirtualByte.one)),
                                      Config.empty) match {
                        case Failure(exception) =>
                          logger.error(
                            s"Error: on server creating transaction $exception")
                          Future.failed(exception)
                        case Success(txIdStr) =>
                          logger.info(s"Successfully created tx: $txIdStr")
                          val txId = DoubleSha256DigestBE(txIdStr)
                          val txHex = ConsoleCli
                            .exec(CliCommand.GetTransaction(txId), Config.empty)
                            .get

                          val db = InvoiceDb(invoice, Transaction(txHex), txId)
                          invoiceDAO.upsert(db)
                      }
                  }
                }
              )

              // This is the good case, where the form was successfully parsed as a Data object.
              widgets += data
              Ok(views.html.showInvoice(invoice))
            }

          Await.result(result, 30.seconds)
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
}
