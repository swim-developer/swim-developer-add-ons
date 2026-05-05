#!/bin/bash

cp /amq/extra/activemq-log-plugin.jar ${CONFIG_INSTANCE_DIR}/lib/

BROKER_XML="${CONFIG_INSTANCE_DIR}/etc/broker.xml"

if ! grep -q "ACKMonitorPlugin" "${BROKER_XML}"; then
    sed -i 's|</addresses>|</addresses>\n\n      <broker-plugins>\n         <broker-plugin class-name="com.github.swim_developer.artemis.plugin.ACKMonitorPlugin"/>\n      </broker-plugins>|' "${BROKER_XML}"
fi

