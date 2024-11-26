package dev.langchain4j.image_to_diagram;

import dev.langchain4j.image_to_diagram.actions.correction.EvaluateResult;
import dev.langchain4j.image_to_diagram.actions.correction.ReviewResult;
import dev.langchain4j.image_to_diagram.actions.correction.RouteEvaluationResult;
import dev.langchain4j.image_to_diagram.serializer.gson.JSONStateSerializer;
import lombok.extern.slf4j.Slf4j;

import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.serializer.StateSerializer;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.utils.CollectionsUtils.mapOf;

@Slf4j( topic="DiagramCorrectionProcess" )
public class DiagramCorrectionProcess implements ImageToDiagram {

    final AsyncNodeAction<State> evaluateResult = new EvaluateResult();
    final AsyncNodeAction<State> reviewResult = new ReviewResult( getModel() );
    final AsyncEdgeAction<State> routeEvaluationResult = edge_async( new RouteEvaluationResult() );

    public StateGraph<State> workflow(StateSerializer<State> stateSerializer ) throws Exception {

        return new StateGraph<>(State.SCHEMA,stateSerializer)
                .addNode( "evaluate_result", evaluateResult)
                .addNode( "agent_review", reviewResult )
                .addEdge( "agent_review", "evaluate_result" )
                .addConditionalEdges("evaluate_result",
                        routeEvaluationResult,
                        mapOf(  "OK", END,
                            "ERROR", "agent_review",
                            "UNKNOWN", END )
                )
                .addEdge( START, "evaluate_result");

    }


    public AsyncGenerator<NodeOutput<State>> execute(Map<String, Object> inputs) throws Exception {

        var stateSerializer = new JSONStateSerializer();

        var workflow = workflow(stateSerializer);

        var app = workflow.compile();

        return app.stream( inputs );

    }
}
