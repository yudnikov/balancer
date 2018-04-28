package ru.yudnikov.balancer

import com.typesafe.config.{Config, ConfigFactory}
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source

class StateSuite extends FlatSpec with Matchers {

  implicit val formats: Formats = DefaultFormats
  implicit val intOrdering: Ordering[BigInt] = (x, y) => y compareTo x

  val rightState = State(List("192.168.0.3" -> 10, "192.168.0.4" -> 5, "192.168.0.5" -> 1), Map())
  val json: String = Source.fromResource("state.json").mkString
  val config: Config = ConfigFactory.load("test.conf")

  "State" should "be restored from json" in {
    val state = Serialization.read[State](json)
    state shouldEqual rightState
  }

  it should "also be read from config" in {
    val state = State(config)
    state shouldEqual rightState
  }

  it should "reserve more than 1 server, when needed" in {
    val newState = rightState.reserve("127.0.0.1", 11)
    newState.reserved shouldBe Map("127.0.0.1" -> Map("192.168.0.3" -> 10, "192.168.0.4" -> 1))
    newState.available shouldBe List("192.168.0.4" -> 4, "192.168.0.5" -> 1)
  }

}
