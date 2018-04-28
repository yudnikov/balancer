package ru.yudnikov.balancer

import ru.yudnikov.balancer.Daemon.{Available, Client, Reserved, ReservedByClient, Server}

import scala.annotation.tailrec
import scala.collection.mutable

/**
  * Class represents current state. Assume, that it should guarantee data consistency, and has strategy "highest
  * throughput "
  *
  * @param available
  * @param reserved
  */
case class State(available: Available, reserved: Reserved) extends Loggable {

  // public API

  /**
    * When client wants to reserve some throughput, may throw an exception
    *
    * @param client
    * @param requested
    * @return new State
    */
  @tailrec
  final def reserve(client: Client, requested: BigInt)(implicit ordering: Ordering[BigInt]): State = {
    available match {
      case (server, amount) :: tail if amount >= requested =>
        val newAvailable = if (amount > requested) server -> (amount - requested) :: tail else tail
        State(newAvailable.sortBy(_._2), newReserved(client, server, requested))
      case (server, amount) :: tail =>
        State(tail, newReserved(client, server, amount)).reserve(client, requested - amount)
      case Nil =>
        throw new Exception(s"Wee need more throughput, My Lord!")
    }
  }

  /**
    * When client wants to release some throughput
    * @param client
    * @return new State
    */
  def release(client: Client)(implicit ordering: Ordering[BigInt]): State = {
    // mutable map for convenience
    val availableMap = mutable.LinkedHashMap(available: _*)
    val newReserved: Reserved = reserved.get(client) match {
      case Some(reservedByClient) =>
        reservedByClient.map { case (server, amount) =>
          availableMap.get(server) match {
            case Some(availAmount) => availableMap += (server -> (availAmount + amount))
            case _ => availableMap += (server -> amount)
          }
        }
        reserved - client
    }
    State(availableMap.toList.sortBy(_._2), newReserved)
  }

  // Public API END

  /**
    * Makes new Reserved, when Client wants to reserve amount of Server's throughput, adds request amount to current
    * reserved data
    * @param client
    * @param server
    * @param amount
    * @return new Reserved
    */
  private def newReserved(client: Client, server: Server, amount: BigInt): Reserved = {
    val reservedByClient: ReservedByClient = reserved.getOrElse(client, Map())
    val newReserveByClient: ReservedByClient = if (reservedByClient contains server)
      reservedByClient + (server -> (reservedByClient(server) + amount))
    else
      reservedByClient + (server -> amount)
    reserved + (client -> newReserveByClient)
  }
}
