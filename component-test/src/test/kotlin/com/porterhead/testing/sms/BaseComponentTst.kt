package com.porterhead.testing.sms

import com.porterhead.testing.RestFunctions
import io.debezium.testing.testcontainers.DebeziumContainer
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.awaitility.Awaitility
import org.junit.BeforeClass
import org.testcontainers.containers.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.MountableFile
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

abstract class BaseComponentTst {

    companion object {
        val network: Network = Network.newNetwork()

        val kafkaContainer = KafkaContainer()
                .withNetwork(network)
                .withNetworkAliases("kafka")

        var postgresContainer: PostgreSQLContainer<*> = KPostgreSQLContainer("debezium/postgres:11")
                .withNetwork(network)
                .withNetworkAliases("postgres-db")
                .withUsername("postgres")
                .withPassword("postgres")
                .withDatabaseName("sms")

        var debeziumContainer = DebeziumContainer("debezium/connect:1.2.1.Final")
                .withNetwork(network)
                .withExposedPorts(8083)
                .withKafka(kafkaContainer)
                .dependsOn(kafkaContainer)

        var keycloakContainer = KGenericContainer("quay.io/keycloak/keycloak:11.0.0")
                .withNetwork(network)
                .withNetworkAliases("keycloak")
                .withExposedPorts(8080)
                .withEnv("KEYCLOAK_USER", "admin")
                .withEnv("KEYCLOAK_PASSWORD", "admin")
                .withEnv("KEYCLOAK_IMPORT", "/tmp/realm.json")
                .withEnv("JAVA_OPTS", "-Dkeycloak.profile.feature.scripts=enabled -Dkeycloak.profile.feature.upload_scripts=enabled")
                .withClasspathResourceMapping("config/porterhead-realm.json", "/tmp/realm.json", BindMode.READ_ONLY)
                .waitingFor(Wait.forHttp("/auth"))

        var smsServiceContainer = KGenericContainer("porterhead/sms-service")
                .withNetwork(network)
                .withNetworkAliases("sms-service")
                .withExposedPorts(8080)
                .withEnv("quarkus.datasource.username", "postgres")
                .withEnv("quarkus.datasource.password", "postgres")
                .withEnv("quarkus.datasource.jdbc.url", "jdbc:postgresql://postgres-db:5432/sms")
                .withCopyFileToContainer(MountableFile.forClasspathResource("/config/application.properties"), "/deployments/config/application.properties")
                .dependsOn(postgresContainer)
                .dependsOn(keycloakContainer)

        var mockServerContainer = KGenericContainer("porterhead/wiremock")
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withExposedPorts(8080)
                .withClasspathResourceMapping("/config/wiremock", "/var/wiremock/mappings", BindMode.READ_WRITE)

        lateinit var restFunctions: RestFunctions

        @BeforeClass
        @JvmStatic
        fun startContainers() {
            kafkaContainer.start()
            val kafkaBootstrap: String = kafkaContainer.bootstrapServers
            System.setProperty("kafka.bootstrap.servers", kafkaBootstrap)
            Startables.deepStart(Stream.of(
                    kafkaContainer,
                    postgresContainer,
                    debeziumContainer,
                    smsServiceContainer,
                    mockServerContainer,
                    keycloakContainer))
                    .join()
            registerKafkaConnector(debeziumContainer.getMappedPort(8083))
            waitForConnector(debeziumContainer.getMappedPort(8083))
            val port = smsServiceContainer.firstMappedPort
            RestAssured.baseURI = "http://localhost:$port"
            //initialize RestFunctions using the mapped port for the keycloak server
            restFunctions =  RestFunctions(keycloakContainer.firstMappedPort)
        }

        fun registerKafkaConnector(port: Int) {
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(connectorRequest)
                    .post("http://localhost:$port/connectors")
        }

        fun waitForConnector(port: Int) {
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).until {
                val response = RestAssured.given()
                        .contentType(ContentType.JSON)
                        .get("http://localhost:$port/connectors/sms-connector")
                        .then()
                        .extract()
                        .response()
                response.statusCode == 200
            }

        }

        val connectorRequest = """
        {
        "name": "sms-connector",
        "config": {
            "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
		    "database.hostname": "postgres-db",
		    "database.port": "5432",
		    "database.user": "postgres",
		    "database.password": "postgres",
		    "database.dbname": "sms",
		    "database.server.name": "smsdb1",
		    "table.whitelist": "public.outboxevent",
		    "transforms": "outbox",
		    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
		    "transforms.OutboxEventRouter.event.key": "aggregate_id",
		    "transforms.outbox.table.fields.additional.placement": "type:header:eventType"
            }
        } 
        """

    }

    class KPostgreSQLContainer(imageName: String) : PostgreSQLContainer<KPostgreSQLContainer>(imageName)

    class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

}
