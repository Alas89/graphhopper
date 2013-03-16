/*
 *  Licensed to Peter Karich under one or more contributor license 
 *  agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  Peter Karich licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except 
 *  in compliance with the License. You may obtain a copy of the 
 *  License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader;

import com.graphhopper.routing.util.AcceptWay;
import com.graphhopper.routing.util.AlgorithmPreparation;
import com.graphhopper.routing.util.FastestCalc;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.VehicleEncoder;
import com.graphhopper.routing.util.NoOpAlgorithmPreparation;
import com.graphhopper.routing.util.PrepareRoutingSubnetworks;
import com.graphhopper.routing.util.RoutingAlgorithmSpecialAreaTests;
import com.graphhopper.routing.util.ShortestCalc;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphStorage;
import com.graphhopper.storage.MMapDirectory;
import com.graphhopper.storage.LevelGraphStorage;
import com.graphhopper.storage.index.Location2IDIndex;
import com.graphhopper.storage.index.Location2IDQuadtree;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Helper;
import static com.graphhopper.util.Helper.*;
import com.graphhopper.util.Helper7;
import com.graphhopper.util.StopWatch;
import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class parses an OSM xml file and creates a graph from it. See run.sh on
 * how to use it from command line.
 *
 * @author Peter Karich,
 */
public class OSMReader {

    public static void main(String[] strs) throws Exception {
        CmdArgs args = CmdArgs.read(strs);
        OSMReader reader = osm2Graph(args);
        Graph g = reader.graph();
        RoutingAlgorithmSpecialAreaTests tests = new RoutingAlgorithmSpecialAreaTests(reader);
        if (args.getBool("osmreader.test", false))
            tests.start();

        if (args.getBool("osmreader.runshortestpath", false)) {
            String type = args.get("osmreader.type", "CAR");
            VehicleEncoder encoder = AcceptWay.parse(type).firstEncoder();
            String algo = args.get("osmreader.algo", "dijkstra");
            int iters = args.getInt("osmreader.algoIterations", 50);
            //warmup            
            tests.runShortestPathPerf(iters / 10, NoOpAlgorithmPreparation.createAlgoPrepare(g, algo, encoder));
            tests.runShortestPathPerf(iters, NoOpAlgorithmPreparation.createAlgoPrepare(g, algo, encoder));
        }
    }
    private static Logger logger = LoggerFactory.getLogger(OSMReader.class);
    private long locations;
    private long skippedLocations;
    private GraphStorage graphStorage;
    private OSMReaderHelper helper;
    private long expectedNodes;
    private AlgorithmPreparation prepare;
    private Location2IDQuadtree index;
    private int indexCapacity = -1;
    private boolean sortGraph = false;

    /**
     * Opens or creates a graph. The specified args need a property 'graph' (a
     * folder) and if no such folder exist it'll create a graph from the
     * provided osm file (property 'osm'). A property 'size' is used to
     * preinstantiate a datastructure/graph to avoid over-memory allocation or
     * reallocation (default is 5mio)
     */
    public static OSMReader osm2Graph(final CmdArgs args) throws IOException {
        if (!Helper.isEmpty(args.get("config", ""))) {
            CmdArgs tmp = CmdArgs.readFromConfig(args.get("config", ""));
            // overwrite command line configuration
            args.merge(tmp);
        }
        String graphLocation = args.get("osmreader.graph-location", "");
        if (Helper.isEmpty(graphLocation)) {
            String strOsm = args.get("osmreader.osm", "");
            if (Helper.isEmpty(strOsm))
                graphLocation = "graph-gh";
            else
                graphLocation = Helper.pruneFileEnd(strOsm) + "-gh";
        }

        File compressed = new File(graphLocation + ".gh");
        if (compressed.exists() && !compressed.isDirectory()) {
            boolean removeZipped = args.getBool("osmreader.graph.removeZipped", true);
            Helper.unzip(compressed.getAbsolutePath(), graphLocation, removeZipped);
        }

        long size = args.getLong("osmreader.size", 10 * 1000);
        GraphStorage storage;
        String dataAccess = args.get("osmreader.dataaccess", "inmemory+save");
        Directory dir;
        if ("mmap".equalsIgnoreCase(dataAccess)) {
            dir = new MMapDirectory(graphLocation);
        } else {
            if ("inmemory+save".equalsIgnoreCase(dataAccess))
                dir = new RAMDirectory(graphLocation, true);
            else
                dir = new RAMDirectory(graphLocation, false);
        }

        String chShortcuts = args.get("osmreader.chShortcuts", "no");
        boolean levelGraph = "true".equals(chShortcuts)
                || "fastest".equals(chShortcuts) || "shortest".equals(chShortcuts);
        if (levelGraph)
            // necessary for simple or CH shortcuts
            storage = new LevelGraphStorage(dir);
        else
            storage = new GraphStorage(dir);
        return osm2Graph(new OSMReader(storage, size), args);
    }

    /**
     * Initializes the specified osmReader with arguments from the args object.
     */
    public static OSMReader osm2Graph(OSMReader osmReader, CmdArgs args) throws IOException {
        osmReader.indexCapacity(args.getInt("osmreader.locationIndexCapacity", -1));
        String type = args.get("osmreader.type", "CAR");
        AcceptWay acceptWay = AcceptWay.parse(type);
        osmReader.acceptStreet(acceptWay);
        final String algoStr = args.get("osmreader.algo", "astar");
        AlgorithmPreparation algoPrepare = NoOpAlgorithmPreparation.
                createAlgoPrepare(algoStr, acceptWay.firstEncoder());
        osmReader.defaultAlgoPrepare(algoPrepare);
        osmReader.sort(args.getBool("osmreader.sortGraph", false));
        osmReader.setCHShortcuts(args.get("osmreader.chShortcuts", "no"));
        if (!osmReader.loadExisting()) {
            String strOsm = args.get("osmreader.osm", "");
            if (Helper.isEmpty(strOsm))
                throw new IllegalArgumentException("Graph not found and no OSM xml provided.");

            File osmXmlFile = new File(strOsm);
            if (!osmXmlFile.exists())
                throw new IllegalStateException("Your specified OSM file does not exist:" + strOsm);
            logger.info("start creating graph from " + osmXmlFile);
            osmReader.osm2Graph(osmXmlFile);
        }
        logger.info("graph " + osmReader.graph().toString());
        return osmReader;
    }

    public OSMReader(String storageLocation, long expectedSize) {
        this(new GraphStorage(new RAMDirectory(storageLocation, true)), expectedSize);
    }

    public OSMReader(GraphStorage storage, long expectedNodes) {
        this.graphStorage = storage;
        this.expectedNodes = expectedNodes;
        helper = createDoubleParseHelper();
        helper.acceptWay(new AcceptWay(true, false, false));
    }

    boolean loadExisting() {
        if (!graphStorage.loadExisting())
            return false;

        // init
        location2IDIndex();
        // load index afterwards
        if (!index.loadExisting())
            throw new IllegalStateException("couldn't load location index");
        return true;
    }

    private InputStream createInputStream(File file) throws IOException {
        FileInputStream fi = new FileInputStream(file);
        if (file.getAbsolutePath().endsWith(".gz"))
            return new GZIPInputStream(fi);
        else if (file.getAbsolutePath().endsWith(".zip"))
            return new ZipInputStream(fi);

        return fi;
    }

    void osm2Graph(File osmXmlFile) throws IOException {
        logger.info("using " + helper.getStorageInfo(graphStorage) + ", accepts:"
                + helper.acceptWay() + ", memory:" + Helper.getMemInfo());
        helper.preProcess(createInputStream(osmXmlFile));
        writeOsm2Graph(createInputStream(osmXmlFile));
        cleanUp();
        optimize();
        flush();
    }

    void optimize() {
        logger.info("optimizing ... (" + Helper.getMemInfo() + ")");
        graphStorage.optimize();
        logger.info("finished optimize (" + Helper.getMemInfo() + ")");

        // move this into the GraphStorage.optimize method?
        if (sortGraph) {
            logger.info("sorting ... (" + Helper.getMemInfo() + ")");
            GraphStorage newGraph = GHUtility.newStorage(graphStorage);
            GHUtility.sortDFS(graphStorage, newGraph);
            graphStorage = newGraph;
        }

        logger.info("calling prepare.doWork ... (" + Helper.getMemInfo() + ")");
        if (prepare == null)
            defaultAlgoPrepare(NoOpAlgorithmPreparation.createAlgoPrepare("dijkstrabi",
                    helper.acceptWay().firstEncoder()));
        else
            prepare.doWork();
    }

    private void cleanUp() {
        helper.cleanup();
        int prev = graphStorage.nodes();
        PrepareRoutingSubnetworks preparation = new PrepareRoutingSubnetworks(graphStorage);
        logger.info("start finding subnetworks, " + Helper.getMemInfo());
        preparation.doWork();
        int n = graphStorage.nodes();
        logger.info("nodes " + n + ", there were " + preparation.subNetworks()
                + " sub-networks. removed them => " + (prev - n)
                + " less nodes. Remaining subnetworks:" + preparation.findSubnetworks().size());
    }

    void flush() {
        logger.info("flushing graph with " + graphStorage.nodes() + " nodes ... (" + Helper.getMemInfo() + ")");
        graphStorage.flush();

        if (indexCapacity < 0)
            indexCapacity = Helper.calcIndexSize(graphStorage.bounds());
        logger.info("initializing and flushing location index with " + indexCapacity + " bounds:" + graphStorage.bounds());
        location2IDIndex().prepareIndex(indexCapacity);
        index.flush();
    }

    /**
     * Creates the edges and nodes files from the specified inputstream (osm xml
     * file).
     */
    void writeOsm2Graph(InputStream is) {
        if (is == null)
            throw new IllegalStateException("Stream cannot be empty");

        // detected nodes means inclusive pillar nodes where we don't need to reserver space for
        int tmp = (int) (helper.expectedNodes() / 50);
        if (tmp < 0 || helper.expectedNodes() == 0)
            throw new IllegalStateException("Expected nodes not in bounds: " + nf(helper.expectedNodes()));

        logger.info("creating graph with expected nodes:" + nf(helper.expectedNodes()));
        graphStorage.createNew(tmp);
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader sReader = null;
        long wayStart = -1;
        StopWatch sw = new StopWatch();
        try {
            sReader = factory.createXMLStreamReader(is, "UTF-8");
            long counter = 1;
            for (int event = sReader.next(); event != XMLStreamConstants.END_DOCUMENT;
                    event = sReader.next(), counter++) {

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        if ("node".equals(sReader.getLocalName())) {
                            processNode(sReader);
                            if (counter % 10000000 == 0) {
                                logger.info(nf(counter) + ", locs:" + nf(locations)
                                        + " (" + skippedLocations + "), edges:" + nf(helper.edgeCount())
                                        + " " + Helper.getMemInfo());
                            }
                        } else if ("way".equals(sReader.getLocalName())) {
                            if (wayStart < 0) {
                                helper.startWayProcessing();
                                logger.info(nf(counter) + ", now parsing ways");
                                wayStart = counter;
                                sw.start();
                            }
                            helper.processWay(sReader);
                            if (counter - wayStart == 10000 && sw.stop().getSeconds() > 1) {
                                logger.warn("Something is wrong! Processing ways takes too long! "
                                        + sw.getSeconds() + "sec for only " + (counter - wayStart) + " entries");
                            }
                            // hmmh a bit hacky: counter does +=2 until the next loop
                            if ((counter / 2) % 1000000 == 0) {
                                logger.info(nf(counter) + ", locs:" + nf(locations)
                                        + " (" + skippedLocations + "), edges:" + nf(helper.edgeCount())
                                        + " " + Helper.getMemInfo());
                            }
                        }

                        break;
                }
            }
            // logger.info("storage nodes:" + storage.nodes() + " vs. graph nodes:" + storage.getGraph().nodes());
        } catch (XMLStreamException ex) {
            throw new RuntimeException("Couldn't process file", ex);
        } finally {
            Helper7.close(sReader);
        }
    }

    private void processNode(XMLStreamReader sReader) throws XMLStreamException {
        long osmId;
        try {
            osmId = Long.parseLong(sReader.getAttributeValue(null, "id"));
        } catch (Exception ex) {
            logger.error("cannot get id from xml node:" + sReader.getAttributeValue(null, "id"), ex);
            return;
        }

        double lat = -1;
        double lon = -1;
        try {
            lat = Double.parseDouble(sReader.getAttributeValue(null, "lat"));
            lon = Double.parseDouble(sReader.getAttributeValue(null, "lon"));
            if (isInBounds(lat, lon)) {
                helper.addNode(osmId, lat, lon);
                locations++;
            } else {
                skippedLocations++;
            }
        } catch (Exception ex) {
            throw new RuntimeException("cannot handle lon/lat of node " + osmId + ": " + lat + "," + lon, ex);
        }
    }

    boolean isInBounds(double lat, double lon) {
        return true;
    }

    /**
     * @return the initialized graph. Invalid if called before osm2Graph.
     */
    public Graph graph() {
        return graphStorage;
    }

    /**
     * Specify the type of the path calculation (car, bike, ...).
     */
    public OSMReader acceptStreet(AcceptWay acceptWays) {
        helper.acceptWay(acceptWays);
        return this;
    }

    public AlgorithmPreparation preparation() {
        return prepare;
    }

    /**
     * Specifies if shortcuts should be introduced (contraction hierarchies) to
     * improve query speed.
     *
     * @param chShortcuts fastest, shortest or false
     */
    public OSMReader setCHShortcuts(String chShortcuts) {
        if (Helper.isEmpty(chShortcuts) || "no".equals(chShortcuts) || "false".equals(chShortcuts))
            return this;

        VehicleEncoder encoder = new CarFlagEncoder();
        int tmpIndex = chShortcuts.indexOf(",");
        if (tmpIndex >= 0)
            encoder = Helper.getVehicleEncoder(chShortcuts.substring(tmpIndex + 1).trim());
        if ("true".equals(chShortcuts) || "fastest".equals(chShortcuts)) {
            prepare = new PrepareContractionHierarchies().type(new FastestCalc(encoder)).vehicle(encoder);
        } else if ("shortest".equals(chShortcuts)) {
            prepare = new PrepareContractionHierarchies().type(new ShortestCalc()).vehicle(encoder);
        } else
            throw new IllegalArgumentException("Value " + chShortcuts + " not valid for configuring "
                    + "contraction hierarchies algorithm preparation");
        prepare.graph(graphStorage);
        return this;
    }

    private OSMReader defaultAlgoPrepare(AlgorithmPreparation defaultPrepare) {
        prepare = defaultPrepare;
        prepare.graph(graphStorage);
        return this;
    }

    OSMReaderHelper createDoubleParseHelper() {
        return new OSMReaderHelperDoubleParse(graphStorage, expectedNodes);
    }

    OSMReaderHelper helper() {
        return helper;
    }

    public Location2IDIndex location2IDIndex() {
        if (index == null)
            index = new Location2IDQuadtree(graphStorage, graphStorage.directory());
        return index;
    }

    /**
     * Changes the default (and automatically calculated) index size of the
     * location2id index to the specified value.
     */
    public OSMReader indexCapacity(int val) {
        indexCapacity = val;
        return this;
    }

    /**
     * Sets if the graph should be sorted to improve query speed. Often not
     * appropriated if graph is huge as sorting is done via copying into a new
     * instance.
     */
    public OSMReader sort(boolean bool) {
        sortGraph = bool;
        return this;
    }
}
