package org.bsc.langgraph4j.studio;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.plain_text.PlainTextStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.bsc.langgraph4j.studio.jetty.LangGraphStreamingServerJetty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;


public interface LangGraphStreamingServer {

    Logger log = LoggerFactory.getLogger(LangGraphStreamingServer.class);

    CompletableFuture<Void> start() throws Exception;

    class NodeOutputSerializer extends StdSerializer<NodeOutput>  {
        Logger log = LangGraphStreamingServer.log;

        protected NodeOutputSerializer() {
            super( NodeOutput.class );
        }

        @Override
        public void serialize(NodeOutput nodeOutput, JsonGenerator gen, SerializerProvider serializerProvider) throws
                IOException {
            log.trace( "NodeOutputSerializer start! {}", nodeOutput.getClass() );
            gen.writeStartObject();
            if( nodeOutput instanceof StateSnapshot<?> snapshot) {
                var checkpoint = snapshot.config().checkPointId();
                log.trace( "checkpoint: {}", checkpoint );
                if( checkpoint.isPresent() ) {
                    gen.writeStringField("checkpoint", checkpoint.get());
                }
            }
            gen.writeStringField("node", nodeOutput.node());
            gen.writeObjectField("state", nodeOutput.state().data());
            gen.writeEndObject();
        }
    }

    record PersistentConfig(String sessionId, String threadId) {
        public PersistentConfig {
            Objects.requireNonNull(sessionId);
        }

    }

    class GraphStreamServlet extends HttpServlet {
        Logger log = LangGraphStreamingServer.log;
        final BaseCheckpointSaver saver;

        final StateGraph<? extends AgentState> stateGraph;
        final ObjectMapper objectMapper;
        final Map<PersistentConfig, CompiledGraph<? extends AgentState>> graphCache = new HashMap<>();

        public GraphStreamServlet(StateGraph<? extends AgentState> stateGraph,
                                  ObjectMapper objectMapper,
                                  BaseCheckpointSaver saver) {

            Objects.requireNonNull(stateGraph, "stateGraph cannot be null");
            this.stateGraph = stateGraph;
            this.objectMapper = objectMapper;
            var module = new SimpleModule();
            module.addSerializer(NodeOutput.class, new NodeOutputSerializer());
            objectMapper.registerModule(module);
            this.saver = saver;
        }

        private CompileConfig compileConfig(PersistentConfig config) {
            return CompileConfig.builder()
                    .checkpointSaver(saver)
                    //.stateSerializer(stateSerializer)
                    .build();
        }

        RunnableConfig runnableConfig(PersistentConfig config ) {
            return RunnableConfig.builder()
                    .threadId(config.threadId())
                    .build();
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            response.setHeader("Accept", "application/json");
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");

            var session = request.getSession(true);
            Objects.requireNonNull(session, "session cannot be null");

            var threadId = ofNullable(request.getParameter("thread"))
                    .orElseThrow(() -> new IllegalStateException("Missing thread id!"));

            var resume = ofNullable(request.getParameter("resume"))
                    .map(Boolean::parseBoolean).orElse(false);

            final PrintWriter writer = response.getWriter();

            // Start asynchronous processing
            var asyncContext = request.startAsync();

            try {

                AsyncGenerator<? extends NodeOutput<? extends AgentState>> generator = null;

                var persistentConfig = new PersistentConfig(session.getId(), threadId);

                var compiledGraph = graphCache.get(persistentConfig);

                final Map<String,Object> dataMap;
                if( resume && stateGraph.getStateSerializer() instanceof PlainTextStateSerializer<? extends AgentState> textSerializer  ) {

                    dataMap = textSerializer.read( new InputStreamReader(request.getInputStream()) ).data();
                }
                else {

                    dataMap = objectMapper.readValue(request.getInputStream(), new TypeReference<>() {
                    });
                }

                if( resume ) {

                    log.trace( "RESUME REQUEST PREPARE" );

                    if (compiledGraph == null) {
                        throw new IllegalStateException( "Missing CompiledGraph in session!" );
                    }

                    var checkpointId = ofNullable(request.getParameter("checkpoint"))
                            .orElseThrow(() -> new IllegalStateException("Missing checkpoint id!"));

                    var node = request.getParameter("node");
                    var config = RunnableConfig.builder()
                            .threadId(threadId)
                            .checkPointId(checkpointId)
                            .build();

                    var stateSnapshot = compiledGraph.getState(config);

                    config = stateSnapshot.config();

                    log.trace( "RESUME UPDATE STATE FORM {} USING CONFIG {}\n{}", node, config, dataMap);

                    config = compiledGraph.updateState(config, dataMap, node );

                    log.trace( "RESUME REQUEST STREAM {}", config);

                    generator = compiledGraph.streamSnapshots(null, config);

                }
                else {

                    log.trace( "dataMap: {}", dataMap );

                    if (compiledGraph == null) {
                        compiledGraph = stateGraph.compile(compileConfig(persistentConfig));
                        graphCache.put(persistentConfig, compiledGraph);
                    }

                    generator = compiledGraph.streamSnapshots(dataMap, runnableConfig(persistentConfig));
                }

                generator.forEachAsync(s -> {
                            try {
                                try {
                                    writer.printf("[ \"%s\",", threadId);
                                    writer.println();
                                    var outputAsString = objectMapper.writeValueAsString(s);
                                    writer.println(outputAsString);
                                    writer.println( "]" );
                                } catch (IOException e) {
                                    log.warn("error serializing state", e);
                                }
                                writer.flush();
                                TimeUnit.SECONDS.sleep(1);
                            } catch ( InterruptedException e) {
                                throw new CompletionException(e);
                            }

                        })
                        .thenAccept(v -> writer.close())
                        .thenAccept(v -> asyncContext.complete())
                        .exceptionally(e -> {
                            log.error("Error streaming", e);
                            writer.close();
                            asyncContext.complete();
                            return null;
                        })
                ;

            } catch (Throwable e) {
                log.error("Error streaming", e);
                throw new ServletException(e);
            }
        }
    }

    record ArgumentMetadata(
            String type,
            boolean required) {
    }

    record ThreadEntry( String id, List<? extends NodeOutput<? extends AgentState>> entries) {

    }

    record InitData(
            String title,
            String graph,
            Map<String, ArgumentMetadata> args,
            List<ThreadEntry> threads
    ) {

        public InitData( String title, String graph, Map<String, ArgumentMetadata> args ) {
            this( title, graph, args, List.of(new ThreadEntry("default", List.of())));
        }
    }

    class InitDataSerializer extends StdSerializer<InitData> {
        Logger log = LangGraphStreamingServer.log;

        protected InitDataSerializer(Class<InitData> t) {
            super(t);
        }

        @Override
        public void serialize(InitData initData, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
            log.trace( "InitDataSerializer start!" );
            jsonGenerator.writeStartObject();

            jsonGenerator.writeStringField("graph", initData.graph());
            jsonGenerator.writeStringField("title", initData.title());
            jsonGenerator.writeObjectField("args", initData.args());


//            jsonGenerator.writeArrayFieldStart("nodes" );
//            for( var node : initData.nodes() ) {
//                jsonGenerator.writeString(node);
//            }
//            jsonGenerator.writeEndArray();

            jsonGenerator.writeArrayFieldStart("threads" );
            for( var thread : initData.threads() ) {
                jsonGenerator.writeStartArray();
                jsonGenerator.writeString(thread.id());
                jsonGenerator.writeStartArray( thread.entries() );
                jsonGenerator.writeEndArray();
                jsonGenerator.writeEndArray();
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeEndObject();
        }
    }

    /**
     * return the graph representation in mermaid format
     */
    class GraphInitServlet extends HttpServlet {

        Logger log = LangGraphStreamingServer.log;

        final StateGraph<? extends AgentState> stateGraph;
        final ObjectMapper objectMapper = new ObjectMapper();
        final InitData initData;

        public GraphInitServlet(StateGraph<? extends AgentState> stateGraph, String title, Map<String, ArgumentMetadata> args) {
            Objects.requireNonNull(stateGraph, "stateGraph cannot be null");
            this.stateGraph = stateGraph;

            var module = new SimpleModule();
            module.addSerializer(InitData.class, new InitDataSerializer(InitData.class));
            objectMapper.registerModule(module);

            var graph = stateGraph.getGraph(GraphRepresentation.Type.MERMAID, title, false);

            initData = new InitData( title, graph.getContent(), args );
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            String resultJson = objectMapper.writeValueAsString(initData);

            log.trace( "{}", resultJson);

            // Start asynchronous processing
            final PrintWriter writer = response.getWriter();
            writer.println(resultJson);
            writer.close();
        }
    }


}

