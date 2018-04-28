package ru.yudnikov.balancer

import java.io.FileWriter
import java.net.InetAddress

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.ask
import com.typesafe.config.{Config, ConfigFactory}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.{Source, StdIn}
import scala.util.{Failure, Success, Try}

object Daemon extends App with Loggable {

  type Client = String
  type Server = String
  type ReservedByClient = Map[Server, Int]
  type Reserved = Map[Client, ReservedByClient]
  type Available = List[(Server, Int)]

  implicit val config: Config = ConfigFactory.load()
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val formats: Formats = DefaultFormats

  // define some strategy which servers may be reserved first
  implicit val intOrdering: Ordering[Int] = (x, y) => y compareTo x

  // may be restored from data/state.json or initialized from config
  var state = {
    logger.debug(s"initializing State")
    lazy val initAvailable: Available = {
      logger.debug(s"no file available, initializing from config")
      config.getObject("servers").unwrapped().asScala.toList.map { case (ip, t) =>
        ip -> t.asInstanceOf[Int]
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
      uri.query() match {
        case Uri.Query.Cons("throughput", str, _) if str.nonEmpty && str.forall(_.isDigit) =>
          Try(state.reserve(address.toString, str.toInt)) match {
            case Success(newState) =>
              logger.debug(s"mutating state to $newState")
              state = newState
              HttpResponse(200, entity = HttpEntity(state.reserved(address.toString).toString))
            case Failure(exception) =>
              logger.error(s"failure", exception)
              HttpResponse(500)
          }
        case x =>
          sys.error(s"Invalid params $x")
      }
    case HttpRequest(POST, uri@Uri.Path("/end"), _, _, _) =>
      logger.debug(s"received GET $uri request")
      Try(state.release(address.toString)) match {
        case Success(newState) =>
          logger.debug(s"mutating state to $newState")
          state = newState
          HttpResponse(200)
        case Failure(exception) =>
          logger.error(s"failure", exception)
          sys.error(exception.getMessage)
      }
    case HttpRequest(GET, Uri.Path("/state"), _, _, _) =>
      HttpResponse(200, entity = HttpEntity(state.toString))
    case _ =>
      HttpResponse(404, entity = "Not found!")
  }

  val bindingFuture: Future[Http.ServerBinding] =
    serverSource.to(Sink.foreach { connection =>
      connection handleWithSyncHandler requestHandler(connection.remoteAddress.getAddress)
    }).run()

  StdIn.readLine(s"press Enter to quit...\n")

  val snapshot = Serialization.writePretty(state)
  val pw = new FileWriter("data/state.json")
  pw.write(snapshot)
  pw.close()
  system.terminate()

}
