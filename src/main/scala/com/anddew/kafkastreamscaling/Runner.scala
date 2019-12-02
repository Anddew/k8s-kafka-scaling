package com.anddew.kafkastreamscaling

import com.typesafe.scalalogging.LazyLogging

object Runner extends App with LazyLogging {

  val streams = WeatherToHotelsKafkaStream.streamsApplication()

  Runtime.getRuntime.addShutdownHook(new Thread(() => streams.close()))

  streams.start()

}
