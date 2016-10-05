package iri;

import java.io.*;
import java.util.*;

class Configuration {

    static final String CONFIGURATION_FILE_NAME = "configuration.iri";
    static final String PROPERTY_PREFIX = "iri.";

    static final String NODES_FILE_NAME = "nodes.iri";
    static final String DEFAULT_API_PORT = "999";

    static final Properties properties = new Properties();

    static {

        try (final FileInputStream configurationInputStream = new FileInputStream(CONFIGURATION_FILE_NAME)) {

            properties.load(configurationInputStream);

        } catch (final IOException e) {

            e.printStackTrace();
        }
    }

    static String coordinator() {

        return properties.getProperty(PROPERTY_PREFIX + "coordinator");
    }

    static int apiPort() {

        return Integer.parseInt(properties.getProperty(PROPERTY_PREFIX + "apiPort", DEFAULT_API_PORT));
    }

    static String apiPassword() {

        return properties.getProperty(PROPERTY_PREFIX + "apiPassword");
    }
}
