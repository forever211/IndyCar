package com.dsc.iu.streaming;

import com.dsc.iu.utils.OnlineLearningUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.storm.shade.com.google.common.util.concurrent.AtomicDouble;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.simple.JSONObject;
import org.numenta.nupic.Parameters;
import org.numenta.nupic.Parameters.KEY;
import org.numenta.nupic.algorithms.Anomaly;
import org.numenta.nupic.algorithms.AnomalyLikelihood;
import org.numenta.nupic.algorithms.SpatialPooler;
import org.numenta.nupic.algorithms.TemporalMemory;
import org.numenta.nupic.network.Network;
import org.numenta.nupic.network.PublisherSupplier;
import org.numenta.nupic.network.sensor.ObservableSensor;
import org.numenta.nupic.network.sensor.Publisher;
import org.numenta.nupic.network.sensor.Sensor;
import org.numenta.nupic.network.sensor.SensorParams;
import org.numenta.nupic.util.Tuple;
import org.numenta.nupic.util.UniversalRandom;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AnomalyDetectionTask extends BaseRichSpout implements MQTTMessageCallback {

    private final static Logger LOG = LogManager.getLogger(AnomalyDetectionTask.class);


    private final static String METRIC_VEHICLE_SPEED = "vehicleSpeed";
    private final static String METRIC_ENGINE_SPEED = "engineSpeed";
    private final static String METRIC_THROTTLE = "throttle";

    private final static String PARAM_CAR_NUMBER = "carNumber";
    private final static String PARAM_LAP_DISTANCE = "lapDistance";
    private final static String PARAM_TIME_OF_DAY = "timeOfDay";
    private final static String PARAM_UUID = "UUID";
    private final static String PARAM_TIME_RECV = "recvTime";
    private final static String PARAM_TIME_SEND = "sendTime";

    private final static String STR_COMMA = ",";
    private final static String STR_UCO = "_";
    private final static String STR_SPACE = " ";
    private final static String STR_ANOMALY = "Anomaly";

    private static final long serialVersionUID = 1L;
    private String inputTopic;
    private String outputTopic;
    private String carNumber;
    private Map<String, Publisher> recordPublisherMap = null;

    private String[] metrics = {METRIC_VEHICLE_SPEED, METRIC_ENGINE_SPEED, METRIC_THROTTLE};

    private Queue<JSONObject> outMessages = new ConcurrentLinkedQueue<>();
    private Map<String, Queue<Double>> anomalyScoreOuts = new HashMap<>();

    private MqttMessage mqttmsg;

    //private Map<String, Long> times = new ConcurrentHashMap<>();

    private MQTTClientInstance mqttClientInstance;

    public AnomalyDetectionTask(String inputTopic) {
        this.inputTopic = inputTopic;
        this.outputTopic = OnlineLearningUtils.sinkoutTopic;

        //todo a temp hack for demo
        this.carNumber = inputTopic.replace("2017","").replace("2018","");
    }

    public AnomalyDetectionTask(String inputTopic, String outputTopic) {
        this.inputTopic = inputTopic;
        this.outputTopic = outputTopic;

        //todo a temp hack for demo
        this.carNumber = inputTopic.replace("2017","").replace("2018","");
	      LOG.info("Taking inputs from : {} and outputing to {}", inputTopic, outputTopic);
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        recordPublisherMap = new HashMap<>();
        mqttClientInstance = MQTTClientInstance.getInstance();
        mqttmsg = new MqttMessage();

        //instantiate the HTM networks for all metrics
        for (String metric : metrics) {
            this.anomalyScoreOuts.put(metric, new ConcurrentLinkedQueue<>());

	          LOG.info("Starting HTM network for metric {}...",metric);
            this.startHTMNetwork(metric);
        }
	      LOG.info("Created all networks. Subscribing to topic...");
        try {
            mqttClientInstance.subscribe(inputTopic, this);
	          LOG.info("Subscribed to topic {}", inputTopic);
        } catch (MqttException e) {
            LOG.error("Error in subscribing to topic{}", inputTopic, e);
        }
    }

    private void syncAndEmit() {
        boolean hasRecords = true;
        for (int i = 0; i < metrics.length; i++) {
            hasRecords &= !this.anomalyScoreOuts.get(metrics[i]).isEmpty();
        }

        if (hasRecords) {
            JSONObject msgToSend = this.outMessages.poll();
            for (int i = 0; i < metrics.length; i++) {
                Double score = this.anomalyScoreOuts.get(metrics[i]).poll();
                if (score == null) {//won't happen, just to make sure nothing breaks
                    score = 0d;
                }
                msgToSend.put(metrics[i] + STR_ANOMALY, score);
            }
            msgToSend.put(PARAM_TIME_SEND, System.currentTimeMillis());
            mqttmsg.setPayload(msgToSend.toJSONString().getBytes());
            mqttmsg.setQos(OnlineLearningUtils.QoS);
            try {
                mqttClientInstance.sendMessage(this.outputTopic, mqttmsg);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void nextTuple() {
        this.syncAndEmit();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        //nothing to do here
    }

    private void startHTMNetwork(String metric) {
        String config = "{\"input\":\"/Users/sahiltyagi/Desktop/numenta_indy2018-13-vspeed.csv\", \"output\":\"/Users/sahiltyagi/Desktop/javaNAB.csv\", "
                + "\"aggregationInfo\": {\"seconds\": 0, \"fields\": [], \"months\": 0, \"days\": 0, \"years\": 0, \"hours\": 0, \"microseconds\": 0, "
                + "\"weeks\": 0, \"minutes\": 0, \"milliseconds\": 0}, \"model\": \"HTMPrediction\", \"version\": 1, \"predictAheadTime\": null, "
                + "\"modelParams\": {\"sensorParams\": {\"sensorAutoReset\": null, \"encoders\": {\"value\": {\"name\": \"value\", "
                + "\"resolution\": 2.5143999999999997, \"seed\": 42, \"fieldname\": \"value\", \"type\": \"RandomDistributedScalarEncoder\"}, "
                + "\"timestamp_dayOfWeek\": null, \"timestamp_timeOfDay\": {\"fieldname\": \"timestamp\", \"timeOfDay\": [21, 9.49], "
                + "\"type\": \"DateEncoder\", \"name\": \"timestamp\"}, \"timestamp_weekend\": null}, \"verbosity\": 0}, "
                + "\"anomalyParams\": {\"anomalyCacheRecords\": null, \"autoDetectThreshold\": null, \"autoDetectWaitRecords\": 5030}, "
                + "\"spParams\": {\"columnCount\": 2048, \"synPermInactiveDec\": 0.0005, \"spatialImp\": \"cpp\", \"inputWidth\": 0, \"spVerbosity\": 0, "
                + "\"synPermConnected\": 0.2, \"synPermActiveInc\": 0.003, \"potentialPct\": 0.8, \"numActiveColumnsPerInhArea\": 40, \"boostStrength\": 0.0, "
                + "\"globalInhibition\": 1, \"seed\": 1956}, \"trainSPNetOnlyIfRequested\": false, \"clParams\": {\"alpha\": 0.035828933612158, "
                + "\"verbosity\": 0, \"steps\": \"1\", \"regionName\": \"SDRClassifierRegion\"}, \"tmParams\": {\"columnCount\": 2048, "
                + "\"activationThreshold\": 20, \"cellsPerColumn\": 32, \"permanenceDec\": 0.008, \"minThreshold\": 13, \"inputWidth\": 2048, "
                + "\"maxSynapsesPerSegment\": 128, \"outputType\": \"normal\", \"initialPerm\": 0.24, \"globalDecay\": 0.0, \"maxAge\": 0, "
                + "\"newSynapseCount\": 31, \"maxSegmentsPerCell\": 128, \"permanenceInc\": 0.04, \"temporalImp\": \"tm_cpp\", \"seed\": 1960, "
                + "\"verbosity\": 0, \"predictedSegmentDecrement\": 0.001}, \"tmEnable\": true, \"clEnable\": false, \"spEnable\": true, "
                + "\"inferenceType\": \"TemporalAnomaly\"}}";

        OptionParser parser = new OptionParser();
        parser.nonOptions("OPF parameters object (JSON)");
        parser.acceptsAll(Arrays.asList("p", "params"), "OPF parameters file (JSON).\n(default: first non-option argument)")
                .withOptionalArg()
                .ofType(File.class);
        parser.acceptsAll(Arrays.asList("i", "input"), "Input data file (csv).\n(default: stdin)")
                .withOptionalArg()
                .ofType(File.class);
        parser.acceptsAll(Arrays.asList("o", "output"), "Output results file (csv).\n(default: stdout)")
                .withOptionalArg()
                .ofType(File.class);
        parser.acceptsAll(Arrays.asList("s", "skip"), "Header lines to skip")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(0);
        parser.acceptsAll(Arrays.asList("h", "?", "help"), "Help");
        OptionSet options = parser.parse(config);

        try {
            JsonNode params;
            ObjectMapper mapper = new ObjectMapper();
            if (options.has("p")) {
                params = mapper.readTree((File) options.valueOf("p"));
            } else if (options.nonOptionArguments().isEmpty()) {
                try {
                } catch (Exception ignore) {
                }
                if (options.has("o")) {
                    try {
                    } catch (Exception ignore) {
                    }
                }
                throw new IllegalArgumentException("Expecting OPF parameters. See 'help' for more information");
            } else {
                params = mapper.readTree((String) options.nonOptionArguments().get(0));
            }

            // Force timezone to UTC
            DateTimeZone.setDefault(DateTimeZone.UTC);
            AnomalyLikelihood likelihood = new AnomalyLikelihood(true, 8640, false, 375, 375);

            PublisherSupplier supplier = PublisherSupplier.builder()
                    .addHeader("timestamp,value")
                    .addHeader("datetime,float")
                    .addHeader("T,B")
                    .build();

            Parameters parameters = getModelParameters(params);

            Network network = Network.create(inputTopic + " " + metric + " Network", parameters)
                    .add(Network.createRegion(inputTopic + " " + metric + " Region")
                            .add(Network.createLayer(inputTopic + " " + metric + " Layer", parameters)
                                    .add(Anomaly.create())
                                    .add(new TemporalMemory())
                                    .add(new SpatialPooler())
                                    .add(Sensor.create(ObservableSensor::create,
                                            SensorParams.create(SensorParams.Keys::obs, metric, supplier)))));

            final AtomicDouble prev_lkhood = new AtomicDouble(0);

            network.observe().subscribe((inference) -> {
                double score = inference.getAnomalyScore();
                double value = (Double) inference.getClassifierInput().get("value").get("inputValue");
                DateTime timestamp = (DateTime) inference.getClassifierInput()
                        .get("timestamp").get("inputValue");
                double prev_likelihood = prev_lkhood.get();

                double anomaly_likelihood = likelihood.anomalyProbability(value, score, timestamp);

                if (anomaly_likelihood >= 0.99999 && prev_likelihood >= 0.99999) {
                    prev_likelihood = anomaly_likelihood;
                    anomaly_likelihood = 0.999;
                } else {
                    prev_likelihood = anomaly_likelihood;
                }

                prev_lkhood.set(prev_likelihood);
                double logscore = AnomalyLikelihood.computeLogLikelihood(anomaly_likelihood);

                this.anomalyScoreOuts.get(metric).add(logscore);
            }, (error) -> {

            }, () -> {


            });

            network.start();
            Publisher publisher = supplier.get();
            LOG.info("Adding publisher {} to {}", publisher, recordPublisherMap);
            recordPublisherMap.put(metric, publisher);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Parameters getModelParameters(JsonNode params) {
        JsonNode modelParams = params.path("modelParams");
        Parameters p = Parameters.getAllDefaultParameters()
                .union(getSpatialPoolerParams(modelParams))
                .union(getTemporalMemoryParams(modelParams))
                .union(getSensorParams(modelParams));

        p.set(KEY.RANDOM, new UniversalRandom(42));
        return p;
    }

    public Parameters getSpatialPoolerParams(JsonNode modelParams) {
        Parameters p = Parameters.getSpatialDefaultParameters();
        JsonNode spParams = modelParams.path("spParams");
        if (spParams.has("columnCount")) {
            p.set(KEY.COLUMN_DIMENSIONS, new int[]{spParams.get("columnCount").asInt()});
        }
        if (spParams.has("maxBoost")) {
            p.set(KEY.MAX_BOOST, spParams.get("maxBoost").asDouble());
        }
        if (spParams.has("synPermInactiveDec")) {
            p.set(KEY.SYN_PERM_INACTIVE_DEC, spParams.get("synPermInactiveDec").asDouble());
        }
        if (spParams.has("synPermConnected")) {
            p.set(KEY.SYN_PERM_CONNECTED, spParams.get("synPermConnected").asDouble());
        }
        if (spParams.has("synPermActiveInc")) {
            p.set(KEY.SYN_PERM_ACTIVE_INC, spParams.get("synPermActiveInc").asDouble());
        }
        if (spParams.has("numActiveColumnsPerInhArea")) {
            p.set(KEY.NUM_ACTIVE_COLUMNS_PER_INH_AREA, spParams.get("numActiveColumnsPerInhArea").asDouble());
        }
        if (spParams.has("globalInhibition")) {
            p.set(KEY.GLOBAL_INHIBITION, spParams.get("globalInhibition").asBoolean());
        }
        if (spParams.has("potentialPct")) {
            p.set(KEY.POTENTIAL_PCT, spParams.get("potentialPct").asDouble());
        }

        return p;
    }

    public Parameters getTemporalMemoryParams(JsonNode modelParams) {
        Parameters p = Parameters.getTemporalDefaultParameters();
        JsonNode tpParams = modelParams.path("tpParams");
        if (tpParams.has("columnCount")) {
            p.set(KEY.COLUMN_DIMENSIONS, new int[]{tpParams.get("columnCount").asInt()});
        }
        if (tpParams.has("activationThreshold")) {
            p.set(KEY.ACTIVATION_THRESHOLD, tpParams.get("activationThreshold").asInt());
        }
        if (tpParams.has("cellsPerColumn")) {
            p.set(KEY.CELLS_PER_COLUMN, tpParams.get("cellsPerColumn").asInt());
        }
        if (tpParams.has("permanenceInc")) {
            p.set(KEY.PERMANENCE_INCREMENT, tpParams.get("permanenceInc").asDouble());
        }
        if (tpParams.has("minThreshold")) {
            p.set(KEY.MIN_THRESHOLD, tpParams.get("minThreshold").asInt());
        }
        if (tpParams.has("initialPerm")) {
            p.set(KEY.INITIAL_PERMANENCE, tpParams.get("initialPerm").asDouble());
        }
        if (tpParams.has("maxSegmentsPerCell")) {
            p.set(KEY.MAX_SEGMENTS_PER_CELL, tpParams.get("maxSegmentsPerCell").asInt());
        }
        if (tpParams.has("maxSynapsesPerSegment")) {
            p.set(KEY.MAX_SYNAPSES_PER_SEGMENT, tpParams.get("maxSynapsesPerSegment").asInt());
        }
        if (tpParams.has("permanenceDec")) {
            p.set(KEY.PERMANENCE_DECREMENT, tpParams.get("permanenceDec").asDouble());
        }
        if (tpParams.has("predictedSegmentDecrement")) {
            p.set(KEY.PREDICTED_SEGMENT_DECREMENT, tpParams.get("predictedSegmentDecrement").asDouble());
        }
        if (tpParams.has("newSynapseCount")) {
            p.set(KEY.MAX_NEW_SYNAPSE_COUNT, tpParams.get("newSynapseCount").intValue());
        }

        return p;
    }

    public Parameters getSensorParams(JsonNode modelParams) {
        JsonNode sensorParams = modelParams.path("sensorParams");
        Map<String, Map<String, Object>> fieldEncodings = getFieldEncodingMap(sensorParams);
        Parameters p = Parameters.empty();
        p.set(KEY.CLIP_INPUT, true);
        p.set(KEY.FIELD_ENCODING_MAP, fieldEncodings);

        return p;
    }

    public Map<String, Map<String, Object>> getFieldEncodingMap(JsonNode modelParams) {
        Map<String, Map<String, Object>> fieldEncodings = new HashMap<>();
        String fieldName;
        Map<String, Object> fieldMap;
        JsonNode encoders = modelParams.path("encoders");
        for (JsonNode node : encoders) {
            if (node.isNull())
                continue;

            fieldName = node.path("fieldname").textValue();
            fieldMap = fieldEncodings.get(fieldName);
            if (fieldMap == null) {
                fieldMap = new HashMap<>();
                fieldMap.put("fieldName", fieldName);
                fieldEncodings.put(fieldName, fieldMap);
            }
            fieldMap.put("encoderType", node.path("type").textValue());
            if (node.has("timeOfDay")) {
                JsonNode timeOfDay = node.get("timeOfDay");
                fieldMap.put("fieldType", "datetime");
                fieldMap.put(KEY.DATEFIELD_PATTERN.getFieldName(), "HH:mm:ss.SSS");
                fieldMap.put(KEY.DATEFIELD_TOFD.getFieldName(),
                        new Tuple(timeOfDay.get(0).asInt(), timeOfDay.get(1).asDouble()));
            } else {
                fieldMap.put("fieldType", "float");
            }
            if (node.has("resolution")) {
                fieldMap.put("resolution", node.get("resolution").asDouble());
            }
        }

        return fieldEncodings;
    }

    final SimpleDateFormat sdfOrigi = new SimpleDateFormat("MM/dd/yy HH:mm:ss.SSS");
    final SimpleDateFormat to = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss.SSS");

    @Override
    public void onMessage(String topic, MqttMessage mqttMessage) {
        try {
            String record = new String(mqttMessage.getPayload());
            String[] splits = record.split(STR_COMMA);
            if (splits.length == 6) {
                double speed = Double.parseDouble(splits[0]);
                double rpm = Double.parseDouble(splits[1]);
                double throttle = Double.parseDouble(splits[2]);
                int record_counter = Integer.parseInt(splits[3]);
                String lapDistance = splits[4];
                String timeOfDay = splits[5];

                JSONObject recordobj = new JSONObject();
                recordobj.put(PARAM_CAR_NUMBER, this.carNumber);
                recordobj.put(PARAM_LAP_DISTANCE, lapDistance);
                recordobj.put(PARAM_TIME_OF_DAY, timeOfDay.split(STR_SPACE)[1]);
                recordobj.put(METRIC_VEHICLE_SPEED, speed);
                recordobj.put(METRIC_ENGINE_SPEED, rpm);
                recordobj.put(METRIC_THROTTLE, throttle);

                final String uuid = this.carNumber + STR_UCO + record_counter;

                recordobj.put(PARAM_TIME_RECV, System.currentTimeMillis());

                recordobj.put(PARAM_UUID, uuid);

                //times.put(uuid, System.currentTimeMillis());

                outMessages.add(recordobj);

                //incomingMessageQueue.add(recordobj);

                for (String metric : metrics) {
                    recordPublisherMap.get(metric).onNext(
                        recordobj.get(PARAM_TIME_OF_DAY) + STR_COMMA + recordobj.get(metric));
                }
            } else {
                LOG.warn("CSV of unknown length {} received. Expected 6", splits.length);
            }
        } catch (Exception ex) {
            LOG.warn("Error in parsing record", ex);
        }
    }
}
