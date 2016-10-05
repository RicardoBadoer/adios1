package iri;

class IRI {

    static final String NAME = "IRI";
    static final String VERSION = "0.9.6";

    public static void main(final String[] args) {

        System.out.println(NAME + " " + VERSION);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            try {

                API.shutDown();
                TipsSelector.shutDown();
                Broadcaster.shutDown();
                Rebroadcaster.shutDown();
                Node.shutDown();
                Storage.shutDown();

            } catch (final Exception e) {
            }

        }, "Shutdown Hook"));

        try {

            if (args.length > 0) {

                ProofOfWorkGenerator.measureHashingPower(Integer.parseInt(args[0]));
            }

            Storage.launch();
            Node.launch();
            Rebroadcaster.launch();
            Broadcaster.launch();
            TipsSelector.launch();
            API.launch();

        } catch (final Exception e) {

            e.printStackTrace();
        }
    }
}
