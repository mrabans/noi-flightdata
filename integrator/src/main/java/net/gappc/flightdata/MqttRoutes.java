package net.gappc.flightdata;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.paho.mqtt5.PahoMqtt5Constants;
import org.apache.camel.component.websocket.WebsocketComponent;

import javax.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;

@ApplicationScoped
public class MqttRoutes extends RouteBuilder {

    private static final int DB_INSERT_MAX_BATCH_SIZE = 100;
    private static final int DB_INSERT_MAX_BATCH_INTERVALL = 1000;
    private static final String FLIGHTDATA_SBS_TOPIC = "flightdata/sbs";

    private final DataSourceProvider dataSourceProvider;
    private final MqttConfig mqttConfig;

    public MqttRoutes(
            DataSourceProvider dataSourceProvider,
            MqttConfig mqttConfig
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.mqttConfig = mqttConfig;
    }

    @Override
    public void configure() throws Exception {
        DataSource dataSource = dataSourceProvider.setupDataSource();
        bindToRegistry("integratorStore", dataSource);

        String mqttConnectionString = getMqttConnectionString();
        System.out.println("-------MQTT------------");
        System.out.println("Connection string: " + mqttConnectionString);
        System.out.println("-------MQTT-END--------");

        from(mqttConnectionString)
                .routeId("[Route: MQTT subscription]")
                .setHeader("topic", header(PahoMqtt5Constants.MQTT_TOPIC))
                .multicast()
                .to("seda:mqttstream?multipleConsumers=true");

        BatchStrategy dbInsertBatchStrategy = new BatchStrategy();

        from("seda:mqttstream?multipleConsumers=true")
                .routeId("[Route: to flightdata table]")
                .log(">>> ${body}")
                .filter(header(PahoMqtt5Constants.MQTT_TOPIC).isEqualTo(FLIGHTDATA_SBS_TOPIC))
                .aggregate(constant(true), dbInsertBatchStrategy)
                .completionSize(DB_INSERT_MAX_BATCH_SIZE)
                .completionTimeout(DB_INSERT_MAX_BATCH_INTERVALL)
                .to("sql:insert into flightdata(topic, body) values (:#topic, :#message::jsonb)?dataSource=#integratorStore&batch=true");

        from("seda:mqttstream?multipleConsumers=true")
                .routeId("[Route: to genericdata table]")
                .log(">>> ${body}")
                .aggregate(constant(true), dbInsertBatchStrategy)
                .completionSize(DB_INSERT_MAX_BATCH_SIZE)
                .completionTimeout(DB_INSERT_MAX_BATCH_INTERVALL)
                .to("sql:insert into genericdata(datasource, type, rawdata) values ('MQTT', :#topic, :#message::jsonb)?dataSource=#integratorStore&batch=true");

        rest("/api")
                .enableCORS(true)
                .get()
                .route()
                .routeId("[Route: REST]")
                .setBody(simple("select id, topic, body#>>'{}' as body, created_at from flightdata order by created_at desc limit 100"))
                .to("jdbc:integratorStore")
                .marshal()
                .json()
                .log(">>> ${body}");

        ((WebsocketComponent)getContext().getComponent("websocket")).setMaxThreads(5);
        from("seda:mqttstream?multipleConsumers=true")
                .routeId("[Route: WebSocket]")
                .filter(header(PahoMqtt5Constants.MQTT_TOPIC).isEqualTo(FLIGHTDATA_SBS_TOPIC))
                .to("websocket://0.0.0.0:8081/flightdata/sbs?sendToAll=true");
    }

    private String getMqttConnectionString() {
        final StringBuilder uri = new StringBuilder(String.format("paho-mqtt5:#?brokerUrl=%s", mqttConfig.url()));

        // Check if MQTT credentials are provided. If so, then add the credentials to the connection string
        mqttConfig.user().ifPresent(user -> uri.append(String.format("&userName=%s", user)));
        mqttConfig.pass().ifPresent(pass -> uri.append(String.format("&password=%s", pass)));

        return uri.toString();
    }
}
