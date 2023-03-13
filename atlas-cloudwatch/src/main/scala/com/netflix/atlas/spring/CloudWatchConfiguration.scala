/*
 * Copyright 2014-2023 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.spring

import akka.actor.ActorSystem
import com.netflix.atlas.akka.AkkaHttpClient
import com.netflix.atlas.akka.DefaultAkkaHttpClient
import com.netflix.atlas.cloudwatch.CloudWatchMetricsProcessor
import com.netflix.atlas.cloudwatch.CloudWatchRules
import com.netflix.atlas.cloudwatch.NetflixTagger
import com.netflix.atlas.cloudwatch.PublishRouter
import com.netflix.atlas.cloudwatch.RedisClusterCloudWatchMetricsProcessor
import com.netflix.atlas.cloudwatch.Tagger
import com.netflix.iep.leader.api.LeaderStatus
import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.Spectator.globalRegistry
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import redis.clients.jedis.Connection
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisCluster

import java.util.Optional

/**
  * Configures the binding for the cloudwatch client and poller.
  */
@Configuration
class CloudWatchConfiguration extends StrictLogging {

  // REPLACE With the dyn config jar include
  @Bean
  def getConfig: Config = ConfigFactory.load()

  @Bean
  def cloudWatchRules(config: Config): CloudWatchRules = new CloudWatchRules(config)

  @Bean
  def tagger(config: Config): Tagger = new NetflixTagger(
    config.getConfig("atlas.cloudwatch.tagger")
  )

  @Bean
  def publishRouter(
    config: Config,
    registry: Optional[Registry],
    tagger: Tagger,
    httpClient: AkkaHttpClient,
    system: ActorSystem
  ): PublishRouter = {
    val r = registry.orElseGet(() => globalRegistry())
    new PublishRouter(config, r, tagger, httpClient)(system)
  }

  @Bean
  def redisCache(
    config: Config,
    registry: Optional[Registry],
    tagger: Tagger,
    jedis: JedisCluster,
    leaderStatus: LeaderStatus,
    rules: CloudWatchRules,
    publishRouter: PublishRouter,
    system: ActorSystem
  ): CloudWatchMetricsProcessor = {
    val r = registry.orElseGet(() => globalRegistry())
    new RedisClusterCloudWatchMetricsProcessor(
      config,
      r,
      tagger,
      jedis,
      leaderStatus,
      rules,
      publishRouter
    )(system)
  }

  @Bean
  def getTagger(
    config: Config
  ): NetflixTagger = {
    new NetflixTagger(config.getConfig("atlas.cloudwatch.tagger"))
  }

  @Bean
  def httpClient(
    system: ActorSystem
  ): DefaultAkkaHttpClient = {
    new DefaultAkkaHttpClient("PubProxy")(system)
  }

  /**
    * Purposely giving each requestee a different cluster client in order to reduce
    * connection pool contention when scraping gauges.
    *
    * @param config
    *   The Typesafe config.
    * @return
    *   A Jedis cluster client.
    */
  @Bean
  def getJedisClient(
    config: Config
  ): JedisCluster = {
    val poolConfig = new GenericObjectPoolConfig[Connection]()
    poolConfig.setMaxTotal(config.getInt("atlas.cloudwatch.redis.connection.pool.max"))
    val cluster =
      config.getString("iep.leader.rediscluster.uri") // RedisClusterConfig.getClusterName(config)
    logger.info(s"Using Redis cluster ${cluster}")
    new JedisCluster(
      new HostAndPort(cluster, config.getInt("atlas.cloudwatch.redis.connection.port")),
      config.getInt("atlas.cloudwatch.redis.cmd.timeout"),
      poolConfig
    )
  }
}