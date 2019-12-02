package com.anddew.kafkastreamscaling

import java.util.Properties

import ch.hsr.geohash.GeoHash
import com.anddew.kafkastreamscaling.WeatherToHotelsKafkaStream._
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.streams.scala.kstream.KTable
import org.apache.kafka.streams.scala.{ImplicitConversions, Serdes, StreamsBuilder}
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig, Topology}

class WeatherToHotelsKafkaStream extends LazyLogging {

  def config(): Properties = {
    val config: Properties = new Properties()

    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "weather-to-hotels-join-streams")
    config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, sys.env("BOOTSTRAP_SERVERS"))
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, classOf[org.apache.kafka.common.serialization.Serdes.StringSerde])
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, classOf[org.apache.kafka.common.serialization.Serdes.StringSerde])
    config.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0.asInstanceOf[Integer])

    config
  }

  def topology(): Topology = {
    import ImplicitConversions._
    import Serdes._

    val builder: StreamsBuilder = new StreamsBuilder()

    val hotels: KTable[String, String] = builder
      .stream[String, String](HOTEL_TOPIC)
      .selectKey((key: String, value: String) => unpack(value).head)
      .peek((key: String, value: String) =>
        logger.info(s"Received record: topic - '$HOTEL_TOPIC', key-value: '$key - $value")
      )
      .groupByKey
      .reduce((oldValue, newValue) => newValue)

    // TODO may be replaced with foreign key join and List[T] SerDe (kafka 2.4.0 at 30 Oct 2019)
    val geoToIdHotelsDimension: KTable[String, String] = hotels
      .groupBy((key: String, value: String) => (extractShortGeoHash(value), unpack(value).head))
      .aggregate(EMPTY_STRING)(
        (key: String, hotelId: String, hotelIds: String) => {
          val hotels = unpack(hotelIds, COLON)
          val result = hotels :+ hotelId
          pack(result, COLON)
        },
        (key: String, hotelId: String, hotelIds: String) => {
          val hotels = unpack(hotelIds, COLON)
          val result = hotels.filter(_ != hotelId)
          pack(result, COLON)
        })

    builder
      .stream[String, String](WEATHER_TOPIC)
      .mapValues(_.replaceAll("\"", ""))
      .mapValues(enrichGeoHashValueMapper)
      .selectKey((key: String, value: String) => extractShortGeoHash(value))
      .peek((key: String, value: String) =>
        logger.error(s"Received record: topic - '$WEATHER_TOPIC', key-value: '$key - $value")
      )
      .join(geoToIdHotelsDimension)(_ + COMMA + _) // TODO a lot of String concat work may be replaced with List[T] SerDe (kafka 2.4.0 at 30 Oct 2019)
      .peek((key: String, value: String) =>
        logger.info(s"Join operation - key-value: '$key - $value")
      )
      .flatMapValues(hotelIdFlatMapper)
      .selectKey((key: String, value: String) => unpack(value).last)
      .join[String, String](hotels)(unpack(_).init :+ _)
      .mapValues(addGeoPrecisionMapper)
      .peek((key: String, value: String) =>
        logger.error(s"Received record: topic - '$OUTPUT_TOPIC', key-value: '$key - $value")
      )
      .to(OUTPUT_TOPIC)

    builder.build
  }

}

object WeatherToHotelsKafkaStream {

  def streamsApplication(): KafkaStreams = {
    val app = new WeatherToHotelsKafkaStream()
    new KafkaStreams(app.topology(), app.config())
  }

  val WEATHER_TOPIC = "weather_topic"
  val HOTEL_TOPIC = "hotels"
  val OUTPUT_TOPIC = "hotel_weather"

  val EMPTY_STRING = ""
  val COMMA = ","
  val COLON = ":"
  val PRECISION = 5

  val enrichGeoHashValueMapper: String => String = (value: String) => {
    val tokens = unpack(value)
    val latitude = tokens(1).toDouble
    val longitude = tokens(0).toDouble
    val geoHash = GeoHash.withCharacterPrecision(latitude, longitude, PRECISION).toBase32
    tokens :+ geoHash
  }

  val hotelIdFlatMapper: String => Iterable[String] = value => {
    val tokens = unpack(value)
    val payload = tokens.init
    val hotelIds = unpack(tokens.last, COLON)
    hotelIds.map(payload + COMMA + _).toIterable
  }

  val addGeoPrecisionMapper: String => String = value => {
    val tokens = unpack(value)
    val weatherGeo = tokens(5)
    val hotelGeo = tokens(13)
    val result = tokens :+ calculatePreсision(weatherGeo, hotelGeo)
    pack(result)
  }

  def calculatePreсision(left: String, right: String): Int = {
    left.zip(right)
      .takeWhile(pair => pair._1 == pair._2)
      .length
  }

  def extractShortGeoHash(value: String): String = unpack(value).last.substring(0, 3)

  def pack[T](value: Array[T], separator: String = COMMA): String = value.mkString(separator)

  def unpack(value: String, separator: String = COMMA): Array[String] = value.split(separator)

  implicit def defaultPack[T](array: Array[T]): String = pack(array)

}

