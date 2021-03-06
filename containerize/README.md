# Setting up IndyCar on Kubernetes

## STEP 1 : Setting up the Kubernetes cluster

A minikube setup can be used to test the distribution locally while it’s recommended to allocate at least  6-8 CPUs and 12-16GB RAM for the minikube VM.

A comprehensive guide on setting up minkube can be found from the blow URL.

https://kubernetes.io/docs/setup/learning-environment/minikube/

## STEP 2 : Setting up the Kubernetes Dashboard

Clone the IndyCar repository to your machine.

```bash
git clone https://github.com/DSC-SPIDAL/IndyCar.git
```

Inside the “containerize” directory, you would find a bash script called “setup_k8.sh”. Navigate to the "containerize" directory and execute setup_k8.sh script to set up the kubernetes dashboard.

```bash
./setup_k8.sh
```

Now, from a web browser of your choice, you should be able to access the Kubernetes dashboard through below URL.

```bash
http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/#/login
```

Use the token printed in the previous step to Sign In.

## STEP 3 : Deploying Zookeeper

We perform IndyCar anomaly detection using an opensource HTM implementation called HTM Java. We have been running HTM Java networks on Apache Storm for convenience and to handle future data stream preprocessing requirements. Apache Storm relies on Zookeeper for coordination between Nimbus and the Supervisors.

Zookeeper can be deployed using kubectl as follows.

```bash
kubectl create -f zookeeper.json
kubectl create -f zookeeper-service.json
```

Allow few minutes to initialize Zookeeper.

## STEP 3 : Deploying Storm Nimbus

Storm nimbus can be deployed as follows.

```bash
kubectl create -f storm-nimbus.json
kubectl create -f storm-nimbus-service.json
```

By default, this definition maps the following directories to the nimbus. If you want to override the default storm configs, 
copy a modified storm.yaml configuration file to /nfs/indycar/config directory. Note that this directory will be created inside the minikube VM.

```json
{
"volumes": [
      {
        "name": "topologies",
        "hostPath": {
          "path": "/nfs/indycar/data"
        }
      },
      {
        "name": "config",
        "hostPath": {
          "path": "/nfs/indycar/config"
        }
      }
    ]
}
```

## STEP 4 : Deploying Storm Dashboard(Optional)

Storm dashboard can be deployed as follows.

```bash
kubectl create -f storm-ui.json
kubectl create -f storm-ui-service.json
```

Now execute following command to get the IP of the minikube VM.

```bash
minikube ip
```

Execute following command to determine the port that has been assigned for strom ui.

```bash
kubectl get services
```

This should give an output similar to below.

```bash
storm-ui                    NodePort    10.98.10.86      <none>        8080:30336/TCP                    5d
```

Now you should be able to access the storm ui via a web browser.

```bash
http://<minikube ip>:30366
```

## STEP 5 : Deploy Strom Supervisors

Strom supervisors should be dynamically scalable. So we deploy them as a Kubernetes replication controller.

In storm-worker-controller.json adjust the resource requirements as per your requirement.

```json
{
  "resources": {
              "limits": {
                "cpu": "11",
                "memory": "100G"
              }
            }
}
```

 ```bash
kubectl create -f storm-worker-controller.json
```

## STEP 6 : Deploy Apollo Broker

We use Apache Apollo broker to enable communication between components(Processes, Pods, Nodes).

Apollo broker can be deployed as follows.

```bash
kubectl create -f activemq-apollo.json
kubectl create -f activemq-apollo-service.json
```

## STEP 7 : Deploy IndyCar Socket Server

IndyCar socket server will be used as both the log file streamer, and the backend for the IndyCar web application.

```bash
kubectl create -f socket-server.yaml
```

Change the below parameters appropriately.

```json
args: ["java", "-jar", "server.jar","/data/logs/eRPGenerator_TGMLP_20170528_Indianapolis500_Race.log","tcp://activemq-apollo:61613","33","2017","compact_topology_out_2017"]
```

``/data/logs/eRPGenerator_TGMLP_20170528_Indianapolis500_Race.log`` is the absolute path to the log file. Copy the log files to the ``/nfs/indycar/datalogs`` of the  minkube VM.

``tcp://activemq-apollo:61613`` is the URL of the apollo broker. This can be kept with this default setting.

``33`` is the number of cars to stream. Reduce this number, if you don't want to stream all 33 cars.

``2017`` is the prefix that will be used when creating broker topics. This can be used to support multiple streams of multiple years within the same cluster.

``compact_topology_out_2017`` is the output topic name. Storm workers should publish the processed data back to this topic.


Similar to the Storm-UI you can determine the port assigned for socket server by executing ``kubectl get services`` command.

## STEP 8 : Building Strom Topology

Pre requisite: Apache Maven

Navigate to the ``streaming`` directory at the root of IndyCar repository and execute below command to build the storm topology.

```bash
mvn clean install
```

Now copy the ``target/Indycar500-33-HTMBaseline-1.0-SNAPSHOT.jar`` to the ``/nfs/indycar/data`` directory of minikube VM.

## STEP 9 : Starting dashboard client application.

Pre requisite: Node Package Manager(NPM)

Navigate to the ``dashboard`` directory at the root of IndyCar repository.

Change the following line of ``src/index.js`` file to point to the IP and the port of the Socket Sever.

```javascript
let socketService = new SocketService("149.165.150.51", 31623, store);
//let socketService = new SocketService("<minikube ip>", <socket server port>, store);
```

Now execute below command within the ``dashboard``  directory to download the required dependencies.

```bash
npm install
```

Now execute below command to start the dashboard locally.

```bash
npm start
```

Now you will be able to access the dashboard from a web browser.

http://localhost:3000

## STEP 8 : Deploy Jupiter Notebook

Jupyter notebook can be used to easily deploy the IndyCar anomaly detection cells and test new components.

```bash
kubectl create -f jupyter.yaml
```

Similar to Storm-UI use ``kubectl get services`` command to determine the port assigned for the notebook.

Now upload IndyCar-API.ipynb to your notebook server and use it appropriately.









