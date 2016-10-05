package iri.ixi;

import iri.ixi.mam.MAM;

import java.util.Map;

public class IXI {

    private static final String MAM_COMMANDS_PREFIX = "mam.";

    public static String process(final Map<String, Object> request) {

        final String command = (String)request.get("command");
        if (command.startsWith(MAM_COMMANDS_PREFIX)) {

            request.put("command", command.substring(MAM_COMMANDS_PREFIX.length()));

            return MAM.process(request);

        } else {

            return null;
        }
    }
}
