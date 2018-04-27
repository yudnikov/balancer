package ru.yudnikov

package object balancer {

  case class Throughput(available: Int, reserved: Map[String, Int])

}
