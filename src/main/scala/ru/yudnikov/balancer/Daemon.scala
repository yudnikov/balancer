package ru.yudnikov.balancer

import java.net.InetAddress

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.ask
import com.typesafe.config.{Config, ConfigFactory}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object Daemon extends App {
  
  implicit val config: Config = ConfigFactory.load()
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  @tailrec
  case class Servers(available: List[(String, Int)], reserved: Map[String, Map[String, Int]]) {
    def reserve(client: String, requested: Int): Servers = {
      available match {
        case (server, amount) :: tail if amount >= requested =>
          val newAvailable = if (amount > requested) server -> (amount - requested) :: tail else tail
          Servers(newAvailable, newReserved(client, server, requested))
        case (server, amount) :: tail =>
          Servers(tail, newReserved(client, server, requested - amount)).reserve(client, requested - amount)
        case Nil =>
          throw new Exception(s"Wee need more servers, My Lord!")
      }
    }
    private def newReserved(client: String, server: String, amount: Int) = {
      val newReserveByClient = {
        val reservedByClient = reserved.getOrElse(client, Map())
        if (reservedByClient contains server) reservedByClient + (server -> (reservedByClient(server) + amount))
        else reservedByClient + (server -> amount)
      }
      reserved + (client -> newReserveByClient)
    }
  }

  val initAvailable: List[(String, Int)] = config.getObject("servers").unwrapped().asScala.toList.map { case (ip, t) =>
    ip -> t.asInstanceOf[Int]
  }
  var state = Servers(initAvailable, Map())

  var serverSource = Http().bind(config.getString("app.host"), config.getInt("app.port"))

  def requestHandler(address: InetAddress): HttpRequest => HttpResponse = {
    case HttpRequest(GET, uri@Uri.Path("/balance"), _, _, _) =>
      uri.query() match {
        case Uri.Query.Cons("throughput", str, _) if str.nonEmpty && str.forall(_.isDigit) =>
          Try(state.reserve(address.toString, str.toInt)) match {
            case Success(servers) =>
              state = servers
              HttpResponse(200, entity = HttpEntity(state.reserved(address.toString).toString()))
            case Failure(exception) =>
              exception.printStackTrace()
              HttpResponse(500)
          }

        case _ =>
          sys.error("BOOM!")
      }
    case HttpRequest(POST, Uri.Path("/end"), _, _, _) =>
      HttpResponse(200)
    case _ =>
      HttpResponse(404, entity = "Not found!")
  }

  val bindingFuture: Future[Http.ServerBinding] =
    serverSource.to(Sink.foreach { connection =>
      connection handleWithSyncHandler requestHandler(connection.remoteAddress.getAddress)
    }).run()

}
