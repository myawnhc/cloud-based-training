package com.hazelcast;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.spi.impl.discovery.HazelcastCloudDiscovery;
import com.hazelcast.client.spi.properties.ClientProperty;
import com.hazelcast.config.GroupConfig;
import com.hazelcast.inv.Inventory;
import com.hazelcast.inv.InventoryDB;
import com.hazelcast.inv.InventoryKey;
import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.pipeline.BatchStage;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;

import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static com.hazelcast.jet.Util.entry;
import static java.util.Map.Entry;

public class PopulateCacheWithJet {

    public Pipeline buildPipeline(ClientConfig remoteIMDGConfig) {
        String connectionURL = InventoryDB.getJdbcURL();
        String user = InventoryDB.getUser();
        String password = InventoryDB.getPassword();
        Pipeline p = Pipeline.create();

        BatchStage<Inventory> source = p.drawFrom(
                // Sources.jdbc takes 3 arguments:
                //  ConnectionSupplier (a JDBC connection subtype)
                // resultSetFn - takes connection, parallelism, and processor index
                //               produces a ResultSet
                // createOutputFn - produces the output object from a ResultSet row
                Sources.jdbc(
                        /* connectionSupplier*/ () -> DriverManager.getConnection(connectionURL, user, password),
                        /* resultSetFn */ (con, parallelism, index) -> {
                            PreparedStatement stmt = con.prepareStatement("select * from inventory where MOD(sku, ?) = ?");
                            stmt.setInt(1, parallelism);
                            stmt.setInt(2, index);
                            System.out.println("Executing " + stmt.toString());
                            return stmt.executeQuery();
                        },
                        /* createOutputFn */ resultSet -> {
                            Inventory item = new Inventory(resultSet.getString(1), /* SKU */
                                          resultSet.getString(2), /* description */
                                          resultSet.getString(3), /* location */
                                          resultSet.getString(4).charAt(0), /* loc type */
                                          resultSet.getInt(5) /* quantity */
                                    );
                            //System.out.println("Java object " + item.toString());
                            return item;
                            }
                        )
        ).setName("Read from database");

        source.setLocalParallelism(4); // See if we can keep multiple threads busy or if we're constrained by
        //                                getting data from the database
        // Initial result - faster with 4 than 2, haven't tried any other values

        // Here we could attach additional pipeline stages, such as
        // filter - filter out unwanted records
        // map - perform transformations on the records
        // hashJoin - join with other data sources

        // Transform the Inventory items to Map.Entry items so we can insert into IMap
        BatchStage<Entry<InventoryKey, Inventory>> asMapEntry = source.map(inv ->
            entry(new InventoryKey(inv.getSKU(), inv.getLocation()), inv)
        ).setName("Convert to Map.Entry");

        asMapEntry.drainTo(Sinks.remoteMap("invmap", remoteIMDGConfig))
                  .setName("Write to IMap (currently local");
        return p;
    }

    public static void main(String[] args) {

        // CloudConfig and ConfigUtil are in the common module
        CloudConfig starterConfig = ConfigUtil.getPersonalClusterConfig();

        // IMDG configuration will be used within the pipeline to access Map remotely
        ClientConfig config = new ClientConfig();
        config.setGroupConfig(new GroupConfig(starterConfig.name, starterConfig.password));
        config.setProperty("hazelcast.client.statistics.enabled", "true");
        config.setProperty(ClientProperty.HAZELCAST_CLOUD_DISCOVERY_TOKEN.getName(), starterConfig.discoveryToken);
        config.setProperty(HazelcastCloudDiscovery.CLOUD_URL_BASE_PROPERTY.getName(), starterConfig.urlBase);

        PopulateCacheWithJet main = new PopulateCacheWithJet();

        JetInstance jet = Jet.newJetInstance();
        Pipeline p = main.buildPipeline(config);
        try {
            long start = System.nanoTime();
            Job j = jet.newJob(p);
            j.join();
            long finish = System.nanoTime();
            long elapsedNanos = finish - start;
            double elapsedSeconds = (double) elapsedNanos / 1_000_000_000D;

            System.out.printf("Finished in %3.3f seconds\n", elapsedSeconds);

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            jet.shutdown();
        }
    }
}
