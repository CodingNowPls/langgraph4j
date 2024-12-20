package org.bsc.langgraph4j.agentexecutor;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.agentexecutor.actions.CallAgent;
import org.bsc.langgraph4j.agentexecutor.actions.ExecuteTools;
import org.bsc.langgraph4j.agentexecutor.serializer.jackson.JSONStateSerializer;
import org.bsc.langgraph4j.agentexecutor.serializer.std.STDStateSerializer;
import org.bsc.langgraph4j.agentexecutor.state.AgentOutcome;
import org.bsc.langgraph4j.agentexecutor.state.IntermediateStep;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.langchain4j.tool.ToolNode;

import java.util.*;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public interface AgentExecutor {

    class State extends AgentState {
        static Map<String, Channel<?>> SCHEMA = Map.of(
                "intermediate_steps", AppenderChannel.<IntermediateStep>of(ArrayList::new)
        );

        public State(Map<String, Object> initData) {
            super(initData);
        }

        public Optional<String> input() {
            return value("input");
        }
        public Optional<AgentOutcome> agentOutcome() {
            return value("agent_outcome");
        }
        public List<IntermediateStep> intermediateSteps() {
            return this.<List<IntermediateStep>>value("intermediate_steps").orElseGet(ArrayList::new);
        }
    }

    enum Serializers {

        STD( new STDStateSerializer() ),
        JSON( new JSONStateSerializer() );

        private final StateSerializer<AgentExecutor.State> serializer;

        Serializers(StateSerializer<AgentExecutor.State> serializer) {
            this.serializer = serializer;
        }

        public StateSerializer<AgentExecutor.State> object() {
            return serializer;
        }

    }

    class GraphBuilder {
        private StreamingChatLanguageModel streamingChatLanguageModel;
        private ChatLanguageModel chatLanguageModel;
        private final ToolNode.Builder toolNodeBuilder = ToolNode.builder();
        private StateSerializer<State> stateSerializer;

        public GraphBuilder chatLanguageModel(ChatLanguageModel chatLanguageModel) {
            this.chatLanguageModel = chatLanguageModel;
            return this;
        }
        public GraphBuilder chatLanguageModel(StreamingChatLanguageModel streamingChatLanguageModel) {
            this.streamingChatLanguageModel = streamingChatLanguageModel;
            return this;
        }
        @Deprecated
        public GraphBuilder objectsWithTools(List<Object> objectsWithTools) {
            objectsWithTools.forEach(toolNodeBuilder::specification);
            return this;
        }
        public GraphBuilder toolSpecification(Object objectsWithTool) {
            toolNodeBuilder.specification( objectsWithTool );
            return this;
        }
        public GraphBuilder toolSpecification(ToolSpecification spec, ToolExecutor executor) {
            toolNodeBuilder.specification( spec, executor );
            return this;
        }
        public GraphBuilder toolSpecification(ToolNode.Specification toolSpecifications) {
            toolNodeBuilder.specification( toolSpecifications );
            return this;
        }

        public GraphBuilder stateSerializer(StateSerializer<State> stateSerializer) {
            this.stateSerializer = stateSerializer;
            return this;
        }

        public StateGraph<State> build() throws GraphStateException {

            if( streamingChatLanguageModel != null && chatLanguageModel != null ) {
                throw new IllegalArgumentException("chatLanguageModel and streamingChatLanguageModel are mutually exclusive!");
            }
            if( streamingChatLanguageModel == null && chatLanguageModel == null ) {
                throw new IllegalArgumentException("a chatLanguageModel or streamingChatLanguageModel is required!");
            }

            final var toolNode = toolNodeBuilder.build();

            var agent = Agent.builder()
                    .chatLanguageModel(chatLanguageModel)
                    .streamingChatLanguageModel(streamingChatLanguageModel)
                    .tools( toolNode.toolSpecifications() )
                    .build();

            if( stateSerializer == null ) {
                stateSerializer = Serializers.STD.object();
            }

            final var callAgent = new CallAgent( agent );
            final var executeTools = new ExecuteTools( agent, toolNode );
            final EdgeAction<State> shouldContinue = (state ) ->
                    state.agentOutcome()
                        .map(AgentOutcome::finish)
                        .map( finish -> "end" )
                        .orElse("continue");

            return new StateGraph<>(State.SCHEMA, stateSerializer)
                    .addNode( "agent",  node_async( callAgent ) )
                    .addNode( "action", node_async( executeTools ) )
                    .addEdge(START,"agent")
                    .addConditionalEdges("agent",
                            edge_async( shouldContinue ),
                            Map.of("continue", "action", "end", END)
                    )
                    .addEdge("action", "agent")
                    ;
        }
    }

    static GraphBuilder graphBuilder() {
        return new GraphBuilder();
    }

}


