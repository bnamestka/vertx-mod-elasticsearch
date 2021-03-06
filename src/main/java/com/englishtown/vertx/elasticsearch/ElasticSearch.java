package com.englishtown.vertx.elasticsearch;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * ElasticSearch event bus verticle
 */
public class ElasticSearch extends BusModBase implements Handler<Message<JsonObject>> {

    protected final TransportClientFactory clientFactory;
    protected TransportClient client;
    protected String address;

    public static final String CONFIG_TRANSPORT_ADDRESSES = "transportAddresses";
    public static final String CONFIG_HOSTNAME = "hostname";
    public static final String CONFIG_PORT = "port";
    public static final String CONFIG_ADDRESS = "address";
    public static final String DEFAULT_ADDRESS = "et.vertx.elasticsearch";
    public static final Charset CHARSET_UTF8 = Charset.forName("UTF-8");

    public static final String CONST_ID = "_id";
    public static final String CONST_INDEX = "_index";
    public static final String CONST_INDICES = "_indices";
    public static final String CONST_TYPE = "_type";
    public static final String CONST_VERSION = "_version";
    public static final String CONST_SOURCE = "_source";

    @Inject
    public ElasticSearch(TransportClientFactory clientFactory) {
        if (clientFactory == null) {
            throw new IllegalArgumentException("clientProvider is null");
        }
        this.clientFactory = clientFactory;
    }

    /**
     * Start the busmod
     */
    @Override
    public void start() {
        super.start();

        Settings settings = ImmutableSettings.settingsBuilder()
                .put("cluster.name", config.getString("cluster_name", "elasticsearch"))
                .put("client.transport.sniff", config.getBoolean("client_transport_sniff", true))
                .build();

        client = clientFactory.create(settings);

        JsonArray transportAddresses = config.getArray(CONFIG_TRANSPORT_ADDRESSES);
        if (transportAddresses != null) {
            for (int i = 0; i < transportAddresses.size(); i++) {
                JsonObject transportAddress = transportAddresses.get(i);
                String hostname = transportAddress.getString(CONFIG_HOSTNAME);

                if (hostname != null && !hostname.isEmpty()) {
                    int port = transportAddress.getInteger(CONFIG_PORT, 9300);
                    client.addTransportAddress(new InetSocketTransportAddress(hostname, port));
                }
            }
        }

        // If no addresses are configured, add local host on the default port
        if (client.transportAddresses().size() == 0) {
            client.addTransportAddress(new InetSocketTransportAddress("localhost", 9300));
        }

        address = config.getString(CONFIG_ADDRESS, DEFAULT_ADDRESS);
        eb.registerHandler(address, this);
    }

    /**
     * Handle an incoming elastic search message
     */
    @Override
    public void handle(Message<JsonObject> message) {

        try {
            String action = getMandatoryString("action", message);
            if (action == null) {
                return;
            }

            switch (action) {
                case "index":
                    doIndex(message);
                    break;
                case "get":
                    doGet(message);
                    break;
                case "search":
                    doSearch(message);
                    break;
                case "scroll":
                    doScroll(message);
                    break;
                default:
                    sendError(message, "Unrecognized action " + action);
                    break;
            }

        } catch (Exception e) {
            sendError(message, "Unhandled exception!", e);
        }

    }

    /**
     * See http://www.elasticsearch.org/guide/reference/api/index_/
     *
     * @param message
     */
    public void doIndex(final Message<JsonObject> message) {

        JsonObject body = message.body();

        final String index = getRequiredIndex(body, message);
        if (index == null) {
            return;
        }

        String type = getRequiredType(body, message);
        if (type == null) {
            return;
        }

        JsonObject source = body.getObject(CONST_SOURCE);
        if (source == null) {
            sendError(message, CONST_SOURCE + " is required");
            return;
        }

        // id is optional
        String id = body.getString(CONST_ID);

        client.prepareIndex(index, type, id)
                .setSource(source.encode())
                .execute(new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(IndexResponse indexResponse) {
                        JsonObject reply = new JsonObject()
                                .putString(CONST_INDEX, indexResponse.getIndex())
                                .putString(CONST_TYPE, indexResponse.getType())
                                .putString(CONST_ID, indexResponse.getId())
                                .putNumber(CONST_VERSION, indexResponse.getVersion());
                        sendOK(message, reply);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        sendError(message, "Index error: " + e.getMessage(), new RuntimeException(e));
                    }
                });

    }

    /**
     * http://www.elasticsearch.org/guide/reference/java-api/get/
     *
     * @param message
     */
    public void doGet(final Message<JsonObject> message) {

        JsonObject body = message.body();

        final String index = getRequiredIndex(body, message);
        if (index == null) {
            return;
        }

        String type = getRequiredType(body, message);
        if (type == null) {
            return;
        }

        String id = body.getString(CONST_ID);
        if (id == null) {
            sendError(message, CONST_ID + " is required");
            return;
        }

        client.prepareGet(index, type, id)
                .execute(new ActionListener<GetResponse>() {
                    @Override
                    public void onResponse(GetResponse getFields) {
                        JsonObject source = (getFields.isExists() ? new JsonObject(getFields.getSourceAsString()) : null);
                        JsonObject reply = new JsonObject()
                                .putString(CONST_INDEX, getFields.getIndex())
                                .putString(CONST_TYPE, getFields.getType())
                                .putString(CONST_ID, getFields.getId())
                                .putNumber(CONST_VERSION, getFields.getVersion())
                                .putObject(CONST_SOURCE, source);
                        sendOK(message, reply);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        sendError(message, "Get error: " + e.getMessage(), new RuntimeException(e));
                    }
                });

    }

    /**
     * http://www.elasticsearch.org/guide/reference/api/search/
     * http://www.elasticsearch.org/guide/reference/query-dsl/
     *
     * @param message
     */
    public void doSearch(final Message<JsonObject> message) {

        JsonObject body = message.body();

        // Get indices to be searched
        String index = body.getString(CONST_INDEX);
        JsonArray indices = body.getArray(CONST_INDICES);
        List<String> list = new ArrayList<>();
        if (index != null) {
            list.add(index);
        }
        if (indices != null) {
            for (int i = 0; i < indices.size(); i++) {
                list.add(indices.<String>get(i));
            }
        }

        SearchRequestBuilder builder = client.prepareSearch(list.toArray(new String[list.size()]));

        // Get types to be searched
        String type = body.getString(CONST_TYPE);
        JsonArray types = body.getArray("_types");
        list.clear();
        if (type != null) {
            list.add(type);
        }
        if (types != null) {
            for (int i = 0; i < types.size(); i++) {
                list.add(types.<String>get(i));
            }
        }
        if (!list.isEmpty()) {
            builder.setTypes(list.toArray(new String[list.size()]));
        }

        // Set the query
        JsonObject query = body.getObject("query");
        if (query != null) {
            builder.setQuery(query.encode());
        }

        // Set the filter
        JsonObject filter = body.getObject("filter");
        if (filter != null) {
            builder.setFilter(filter.encode());
        }

        // Set facets
        JsonObject facets = body.getObject("facets");
        if (facets != null) {
            builder.setFacets(facets.encode().getBytes(CHARSET_UTF8));
        }

        // Set search type
        String searchType = body.getString("search_type");
        if (searchType != null) {
            builder.setSearchType(searchType);
        }

        // Set scroll keep alive time
        String scroll = body.getString("scroll");
        if (scroll != null) {
            builder.setScroll(scroll);
        }

        builder.execute(new ActionListener<SearchResponse>() {
            @Override
            public void onResponse(SearchResponse searchResponse) {
                handleActionResponse(searchResponse, message);
            }

            @Override
            public void onFailure(Throwable e) {
                sendError(message, "Search error: " + e.getMessage(), new RuntimeException(e));
            }
        });

    }

    /**
     * http://www.elasticsearch.org/guide/reference/api/search/scroll/
     *
     * @param message
     */
    public void doScroll(final Message<JsonObject> message) {

        JsonObject body = message.body();
        String scrollId = body.getString("_scroll_id");
        if (scrollId == null) {
            sendError(message, "_scroll_id is required");
            return;
        }

        String scroll = body.getString("scroll");
        if (scroll == null) {
            sendError(message, "scroll is required");
            return;
        }

        client.prepareSearchScroll(scrollId)
                .setScroll(scroll)
                .execute(new ActionListener<SearchResponse>() {
                    @Override
                    public void onResponse(SearchResponse searchResponse) {
                        handleActionResponse(searchResponse, message);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        sendError(message, "Scroll error: " + e.getMessage(), new RuntimeException(e));
                    }
                });

    }

    protected String getRequiredIndex(JsonObject json, Message<JsonObject> message) {
        String index = json.getString(CONST_INDEX);
        if (index == null || index.isEmpty()) {
            sendError(message, CONST_INDEX + " is required");
            return null;
        }
        return index;
    }

    protected String getRequiredType(JsonObject json, Message<JsonObject> message) {
        String type = json.getString(CONST_TYPE);
        if (type == null || type.isEmpty()) {
            sendError(message, CONST_TYPE + " is required");
            return null;
        }
        return type;
    }

    protected void handleActionResponse(ToXContent toXContent, Message<JsonObject> message) {

        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            toXContent.toXContent(builder, SearchResponse.EMPTY_PARAMS);
            builder.endObject();

            JsonObject response = new JsonObject(builder.string());
            sendOK(message, response);

        } catch (IOException e) {
            sendError(message, "Error reading search response: " + e.getMessage(), e);
        }

    }
}
