package ru.yudnikov.balancer

import java.io.FileWriter
import java.net.InetAddress

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.config.{Config, ConfigFactory}
import org.json4s.{DefaultFormats, Formats, FullTypeHints}
import org.json4s.jackson.Serialization

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.{Source, StdIn}
import scala.util.{Failure, Success, Try}

object Daemon extends App with Loggable {

  type Client = String
  type Server = String
  type ReservedByClient = Map[Server, BigInt]
  type Reserved = Map[Client, ReservedByClient]
  type Available = List[(Server, BigInt)]

  implicit val config: Config = ConfigFactory.load()
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val formats: Formats = DefaultFormats.withHints(FullTypeHints.apply(List(classOf[BigInt])))
  // define some strategy which servers may be reserved first
  implicit val intOrdering: Ordering[BigInt] = (x, y) => y compareTo x

  // may be restored from data/state.json or initialized from config
  var state: State = {
    logger.debug(s"initializing State")
    lazy val initAvailable: Available = {
      logger.debug(s"no file available, initializing from config")
      config.getObject("servers").unwrapped().asScala.toList.map { case (ip, t) =>
        ip -> t.asInstanceOf[BigInt]
      }
    }
    Try(Source.fromFile("data/state.json")).map { file =>
      val json = file.mkString
      logger.debug(s"state is taken from file:\n\t$json")
      Serialization.read[State](json)
    }.toOption.getOrElse(State(initAvailable.sortBy(_._2), Map()))
  }

  var serverSource = Http().bind(config.getString("app.host"), config.getInt("app.port"))

  def requestHandler(address: InetAddress): HttpRequest => HttpResponse = {
    case HttpRequest(GET, uri@Uri.Path("/balance"), _, _, _) =>
      logger.debug(s"received GET $uri request")
      handleGetBalance(address, uri)
    case HttpRequest(POST, uri@Uri.Path("/end"), _, _, _) =>
      logger.debug(s"received GET $uri request")
      handlePostEnd(address)
    case HttpRequest(GET, Uri.Path("/state"), _, _, _) =>
      HttpResponse(200, entity = HttpEntity(state.toString))
    case _ =>
      HttpResponse(404, entity = "Not found!")
  }

  private def handlePostEnd(address: InetAddress): HttpResponse = {
    Try(state.release(address.toString)) match {
      case Success(newState) =>
        logger.debug(s"mutating state to $newState")
        state = newState
        HttpResponse(200)
      case Failure(exception) =>
        logger.error(s"failure", exception)
        sys.error(exception.getMessage)
    }
  }

  private def handleGetBalance(address: InetAddress, uri: Uri): HttpResponse = {
    uri.query() match {
      case Uri.Query.Cons("throughput", str, _) if str.nonEmpty && str.forall(_.isDigit) =>
        Try(state.reserve(address.toString, str.toInt)) match {
          case Success(newState) =>
            logger.debug(s"mutating state to $newState")
            state = newState
            val answer = Serialization.write(state.reserved(address.toString))
            HttpResponse(200, entity = HttpEntity(answer))
          case Failure(exception) =>
            logger.error(s"failure", exception)
            HttpResponse(500)
        }
      case x =>
        sys.error(s"Invalid params $x")
    }
  }

  val bindingFuture: Future[Http.ServerBinding] =
    serverSource.to(Sink.foreach { connection =>
      connection handleWithSyncHandler requestHandler(connection.remoteAddress.getAddress)
    }).run()

  StdIn.readLine(s"press Enter to quit...\n")

  private def takeSnapshot(): Unit = {
    logger.info(s"taking snapshot")
    val snapshot = Serialization.writePretty(state)
    val pw = new FileWriter("data/state.json")
    pw.write(snapshot)
    logger.info(snapshot)
    pw.close()
  }

  Try(takeSnapshot())

  system.terminate()

}
