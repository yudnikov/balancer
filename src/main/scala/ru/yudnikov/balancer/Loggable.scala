package ru.yudnikov.balancer

import com.typesafe.scalalogging.Logger

trait Loggable {

  protected val logger = Logger(getClass)

}
