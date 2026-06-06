#!/bin/bash

mvn clean package
cp target/activemq-plugins.jar ../infra/openshift/amq-broker
oc delete secret activemq-plugins
oc create secret generic activemq-plugins --from-file=target/activemq-plugins.jar
oc delete pod artemis-swim-ss-0