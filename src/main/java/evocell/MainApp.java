package evocell;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.FileChooser;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

interface Node {
    int evaluate(int currentCell, int[] neighbors);

    Node deepCopy();

    String toPrettyString(int indent);

    String toSerializedString();

    String getName();

    List<Node> getChildren();

    List<Node> getInputs();

    void addInput(Node input);

    Node forStateCount(int totalStates);
}

abstract class NodeBase implements Node {
    private static final ThreadLocal<IdentityHashMap<Node, Boolean>> ACTIVE_EVALUATIONS =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<IdentityHashMap<Node, Boolean>> ACTIVE_SERIALIZATIONS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private volatile List<Node> inputs = List.of();

    @Override
    public final List<Node> getInputs() {
        return inputs;
    }

    @Override
    public final synchronized void addInput(Node input) {
        Objects.requireNonNull(input, "input");
        if (inputs.contains(input)) {
            return;
        }
        List<Node> updatedInputs = new ArrayList<>(inputs);
        updatedInputs.add(input);
        inputs = List.copyOf(updatedInputs);
    }

    final synchronized void clearInputs() {
        inputs = List.of();
    }

    protected final int sumInputs(int currentCell, int[] neighbors) {
        long sum = 0L;
        for (Node input : getInputs()) {
            sum += input.evaluate(currentCell, neighbors);
            if (sum > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (sum < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
        }
        return (int) sum;
    }

    protected final int evaluateWithCycleGuard(IntSupplier evaluation) {
        IdentityHashMap<Node, Boolean> active = ACTIVE_EVALUATIONS.get();
        if (active.containsKey(this)) {
            return 0;
        }
        active.put(this, Boolean.TRUE);
        try {
            return evaluation.getAsInt();
        } finally {
            active.remove(this);
        }
    }

    protected final String serializeWithCycleGuard(Supplier<String> serialization) {
        IdentityHashMap<Node, Boolean> active = ACTIVE_SERIALIZATIONS.get();
        if (active.containsKey(this)) {
            throw new IllegalStateException(
                    "Cannot serialize cyclic node connections with the current file format.");
        }
        active.put(this, Boolean.TRUE);
        try {
            return serialization.get();
        } finally {
            active.remove(this);
            if (active.isEmpty()) {
                ACTIVE_SERIALIZATIONS.remove();
            }
        }
    }

    protected final String appendSerializedInputs(String body) {
        List<Node> currentInputs = getInputs();
        StringBuilder serialized = new StringBuilder(body);
        if (!currentInputs.isEmpty()) {
            serialized.append(" IN:").append(currentInputs.size());
            for (Node input : currentInputs) {
                serialized.append(' ').append(input.toSerializedString());
            }
        }
        return serialized.append(" END").toString();
    }

    protected final <T extends NodeBase> T copyInputsTo(T copy) {
        for (Node input : inputs) {
            copy.addInput(input.deepCopy());
        }
        return copy;
    }
}

final class TemporalEvaluationContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TemporalEvaluationContext() {
    }

    static int getPastState(int offset) {
        Context context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException(
                    "GET_PAST_STATE can only be evaluated during a simulation step.");
        }
        if (offset > context.pastGrids.length) {
            return 0;
        }
        return context.pastGrids[offset - 1][context.cellIndex];
    }

    static int evaluate(
            int[][] pastGrids, int cellIndex, java.util.function.IntSupplier evaluation) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(pastGrids, cellIndex));
        try {
            return evaluation.getAsInt();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private record Context(int[][] pastGrids, int cellIndex) {
    }
}

final class NodeTraversal {
    private NodeTraversal() {
    }

    static boolean dependsOn(Node start, Node target) {
        return dependsOn(start, target, new IdentityHashMap<>());
    }

    private static boolean dependsOn(
            Node current, Node target, IdentityHashMap<Node, Boolean> visited) {
        if (current == target) {
            return true;
        }
        if (visited.put(current, Boolean.TRUE) != null) {
            return false;
        }
        for (Node dependency : dependencies(current)) {
            if (dependsOn(dependency, target, visited)) {
                return true;
            }
        }
        return false;
    }

    static List<Node> dependencies(Node node) {
        List<Node> dependencies = new ArrayList<>(
                node.getInputs().size() + node.getChildren().size());
        dependencies.addAll(node.getInputs());
        dependencies.addAll(node.getChildren());
        return dependencies;
    }
}

final class ConstantNode extends NodeBase {
    private final int val;

    ConstantNode(int val) {
        val = Math.floorMod(val, 32);
        this.val = val;
    }

    int val() {
        return val;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> val);
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return indentation(indent) + val;
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs("C:" + val));
    }

    @Override
    public String getName() {
        return "Constant: " + val;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }

    private static String indentation(int indent) {
        return "  ".repeat(Math.max(0, indent));
    }
}

final class CurrentStateNode extends NodeBase {
    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> currentCell);
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "SELF";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs("S"));
    }

    @Override
    public String getName() {
        return "Current cell state";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class NeighborCountNode extends NodeBase {
    private final int searchState;

    NeighborCountNode(int searchState) {
        searchState = Math.floorMod(searchState, 32);
        this.searchState = searchState;
    }

    int searchState() {
        return searchState;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            int count = 0;
            for (int neighbor : neighbors) {
                if (neighbor == searchState) {
                    count++;
                }
            }
            return clampToInt((long) count + sumInputs(currentCell, neighbors));
        });
    }

    private static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "COUNT_NEIGHBORS(" + searchState + ")";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("N:" + searchState));
    }

    @Override
    public String getName() {
        return "Count neighbors in state " + searchState;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class GetNeighborByDirectionNode extends NodeBase {
    private final String direction;

    GetNeighborByDirectionNode(String direction) {
        this.direction = normalizeDirection(direction);
    }

    String direction() {
        return direction;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        Objects.requireNonNull(neighbors, "neighbors");
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("A Moore neighborhood must contain 8 entries.");
        }
        return evaluateWithCycleGuard(() -> neighbors[directionIndex(direction)]);
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent))
                + "GET_NEIGHBOR(\"" + direction + "\")";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("D:" + direction));
    }

    @Override
    public String getName() {
        return "Neighbor state: " + direction;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }

    static String normalizeDirection(String direction) {
        Objects.requireNonNull(direction, "direction");
        String normalized = direction.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
        return switch (normalized) {
            case "TOP", "NORTH", "N" -> "North";
            case "RIGHT", "EAST", "E" -> "East";
            case "BOTTOM", "SOUTH", "S" -> "South";
            case "LEFT", "WEST", "W" -> "West";
            case "NORTH-WEST", "NORTHWEST", "TOP-LEFT", "NW" -> "North-West";
            case "NORTH-EAST", "NORTHEAST", "TOP-RIGHT", "NE" -> "North-East";
            case "SOUTH-WEST", "SOUTHWEST", "BOTTOM-LEFT", "SW" -> "South-West";
            case "SOUTH-EAST", "SOUTHEAST", "BOTTOM-RIGHT", "SE" -> "South-East";
            default -> throw new IllegalArgumentException(
                    "Unsupported neighbor direction: " + direction);
        };
    }

    static int directionIndex(String direction) {
        return switch (direction) {
            case "North-West" -> 0;
            case "North" -> 1;
            case "North-East" -> 2;
            case "West" -> 3;
            case "East" -> 4;
            case "South-West" -> 5;
            case "South" -> 6;
            case "South-East" -> 7;
            default -> throw new IllegalStateException(
                    "Unsupported normalized direction: " + direction);
        };
    }
}

final class NeighborIsNode extends NodeBase {
    private final String direction;
    private final int targetState;

    NeighborIsNode(String direction, int targetState) {
        this.direction = GetNeighborByDirectionNode.normalizeDirection(direction);
        if (targetState < 0 || targetState >= 32) {
            throw new IllegalArgumentException("Target state must be between 0 and 31.");
        }
        this.targetState = targetState;
    }

    String direction() {
        return direction;
    }

    int targetState() {
        return targetState;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        Objects.requireNonNull(neighbors, "neighbors");
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("A Moore neighborhood must contain 8 entries.");
        }
        return evaluateWithCycleGuard(
                () -> neighbors[GetNeighborByDirectionNode.directionIndex(direction)]
                        == targetState ? 1 : 0);
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + toString();
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("NEIGHBOR_IS:" + direction + ":" + targetState));
    }

    @Override
    public String getName() {
        return "Neighbor " + direction + " is state " + targetState;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        int validatedStateCount = StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(
                new NeighborIsNode(direction, Math.floorMod(targetState, validatedStateCount)),
                validatedStateCount);
    }

    @Override
    public String toString() {
        return "NEIGHBOR_IS(\"" + direction + "\", " + targetState + ")";
    }
}

final class CountSameNeighborsNode extends NodeBase {
    private static final int[] CARDINAL_DIRECTIONS = {1, 4, 6, 3};

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        Objects.requireNonNull(neighbors, "neighbors");
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("A Moore neighborhood must contain 8 entries.");
        }
        return evaluateWithCycleGuard(() -> {
            int count = 0;
            for (int direction : CARDINAL_DIRECTIONS) {
                if (neighbors[direction] == currentCell) {
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + toString();
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs("COUNT_SAME"));
    }

    @Override
    public String getName() {
        return "Count cardinal neighbors matching the current state";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }

    @Override
    public String toString() {
        return "COUNT_SAME_NEIGHBORS()";
    }
}

final class IsLineNode extends NodeBase {
    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        Objects.requireNonNull(neighbors, "neighbors");
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("A Moore neighborhood must contain 8 entries.");
        }
        return evaluateWithCycleGuard(() -> {
            boolean verticalAxis = neighbors[1] == neighbors[6] && neighbors[1] > 0;
            boolean horizontalAxis = neighbors[3] == neighbors[4] && neighbors[3] > 0;
            return verticalAxis || horizontalAxis ? 1 : 0;
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + toString();
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs("IS_LINE"));
    }

    @Override
    public String getName() {
        return "Detect matching occupied opposite neighbors";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }

    @Override
    public String toString() {
        return "IS_LINE()";
    }
}

final class CountAllNeighborsNode extends NodeBase {
    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        Objects.requireNonNull(neighbors, "neighbors");
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("A Moore neighborhood must contain 8 entries.");
        }
        return evaluateWithCycleGuard(() -> {
            int occupiedNeighbors = 0;
            for (int neighbor : neighbors) {
                if (neighbor > 0) {
                    occupiedNeighbors++;
                }
            }
            return occupiedNeighbors;
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "COUNT_ALL_NEIGHBORS";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("COUNT_ALL"));
    }

    @Override
    public String getName() {
        return "Count all non-empty neighbors";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class GetPastStateNode extends NodeBase {
    private final int offset;

    GetPastStateNode(int offset) {
        if (offset < 1 || offset > 2) {
            throw new IllegalArgumentException("Past-state offset must be 1 or 2.");
        }
        this.offset = offset;
    }

    int offset() {
        return offset;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> TemporalEvaluationContext.getPastState(offset));
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "GET_PAST_STATE(" + offset + ")";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("PAST:" + offset));
    }

    @Override
    public String getName() {
        return "Past cell state: " + offset + " generation(s)";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class SetNextStateNode extends NodeBase {
    private final Node valueNode;

    SetNextStateNode(Node valueNode) {
        this.valueNode = Objects.requireNonNull(valueNode, "valueNode");
    }

    Node valueNode() {
        return valueNode;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> valueNode.evaluate(currentCell, neighbors));
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "SET_NEXT_STATE("
                + NodeFormatting.inline(valueNode) + ")";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("SET_NEXT " + valueNode.toSerializedString()));
    }

    @Override
    public String getName() {
        return "Set next cell state";
    }

    @Override
    public List<Node> getChildren() {
        return List.of(valueNode);
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, StateCounts.checked(totalStates));
    }
}

final class ChanceNode extends NodeBase {
    private final int probability;

    ChanceNode(int probability) {
        if (probability < 0 || probability > 100) {
            throw new IllegalArgumentException("Probability must be between 0 and 100.");
        }
        this.probability = probability;
    }

    int probability() {
        return probability;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            int roll = ThreadLocalRandom.current().nextInt(1, 101);
            if (roll > probability) {
                return 0;
            }
            List<Node> inputs = getInputs();
            return inputs.isEmpty() ? 1 : inputs.get(0).evaluate(currentCell, neighbors);
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "CHANCE(" + probability + "%)";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("CHANCE:" + probability));
    }

    @Override
    public String getName() {
        return "Chance: " + probability + "%";
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class RandomIntNode extends NodeBase {
    private final int min;
    private final int max;

    RandomIntNode(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Random range minimum must not exceed maximum.");
        }
        this.min = min;
        this.max = max;
    }

    int min() {
        return min;
    }

    int max() {
        return max;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(
                () -> (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1L));
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent))
                + "RANDOM_INT(" + min + ", " + max + ")";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("RANDOM_INT:" + min + ":" + max));
    }

    @Override
    public String getName() {
        return "Random integer: " + min + ".." + max;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        StateCounts.checked(totalStates);
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class SwitchCaseNode extends NodeBase {
    private final Node selector;
    private final Map<Integer, Node> cases = new java.util.LinkedHashMap<>();

    SwitchCaseNode(Node selector) {
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    SwitchCaseNode(Node selector, Map<Integer, Node> cases) {
        this(selector);
        Objects.requireNonNull(cases, "cases").forEach(this::addCase);
    }

    Node selector() {
        return selector;
    }

    Map<Integer, Node> cases() {
        return java.util.Collections.unmodifiableMap(cases);
    }

    void addCase(int value, Node result) {
        cases.put(value, Objects.requireNonNull(result, "result"));
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            List<Node> inputs = getInputs();
            int selectedValue = inputs.isEmpty()
                    ? selector.evaluate(currentCell, neighbors)
                    : inputs.get(0).evaluate(currentCell, neighbors);
            Node result = cases.get(selectedValue);
            return result == null ? 0 : result.evaluate(currentCell, neighbors);
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        String padding = "  ".repeat(Math.max(0, indent));
        StringBuilder text = new StringBuilder(padding)
                .append("SWITCH (").append(NodeFormatting.inline(selector)).append(')');
        for (Map.Entry<Integer, Node> entry : cases.entrySet()) {
            text.append('\n').append(padding).append("  CASE ")
                    .append(entry.getKey()).append(": ")
                    .append(NodeFormatting.inline(entry.getValue()));
        }
        return text.toString();
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> {
            StringBuilder body = new StringBuilder("SWITCH:")
                    .append(cases.size()).append(' ')
                    .append(selector.toSerializedString());
            for (Map.Entry<Integer, Node> entry : cases.entrySet()) {
                body.append(' ').append(entry.getKey()).append(' ')
                        .append(entry.getValue().toSerializedString());
            }
            return appendSerializedInputs(body.toString());
        });
    }

    @Override
    public String getName() {
        return "Switch / Case";
    }

    @Override
    public List<Node> getChildren() {
        List<Node> children = new ArrayList<>(cases.size() + 1);
        children.add(selector);
        children.addAll(cases.values());
        return List.copyOf(children);
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, StateCounts.checked(totalStates));
    }
}

final class StateCounts {
    private StateCounts() {
    }

    static int checked(int totalStates) {
        if (totalStates < 2 || totalStates > 32) {
            throw new IllegalArgumentException("totalStates must be between 2 and 32");
        }
        return totalStates;
    }
}

final class NodeCopies {
    private NodeCopies() {
    }

    static Node deepCopy(Node root) {
        return copy(root, null, new IdentityHashMap<>(), false);
    }

    static Node forStateCount(Node root, int totalStates) {
        return copy(root, StateCounts.checked(totalStates), new IdentityHashMap<>(), false);
    }

    static Node forGollyDeterministic(Node root, int totalStates) {
        return copy(root, StateCounts.checked(totalStates), new IdentityHashMap<>(), true);
    }

    private static Node copy(
            Node source,
            Integer stateCount,
            IdentityHashMap<Node, Node> copies,
            boolean freezeStochasticNodes) {
        Node existing = copies.get(source);
        if (existing != null) {
            return existing;
        }

        Node copied;
        if (freezeStochasticNodes && source instanceof ChanceNode chance) {
            List<Node> inputs = source.getInputs();
            copied = chance.probability() > 50
                    ? inputs.isEmpty()
                            ? new ConstantNode(1)
                            : copy(inputs.get(0), stateCount, copies, true)
                    : new ConstantNode(0);
            copies.put(source, copied);
            return copied;
        } else if (freezeStochasticNodes && source instanceof RandomIntNode randomInt) {
            copied = new ConstantNode(randomInt.min());
        } else if (freezeStochasticNodes && source instanceof GetPastStateNode) {
            copied = new CurrentStateNode();
        } else if (freezeStochasticNodes && source instanceof SetNextStateNode setNextState) {
            copied = copy(setNextState.valueNode(), stateCount, copies, true);
            copies.put(source, copied);
            return copied;
        } else if (source instanceof ConstantNode constant) {
            int value = stateCount == null
                    ? constant.val()
                    : Math.floorMod(constant.val(), stateCount);
            copied = new ConstantNode(value);
        } else if (source instanceof CurrentStateNode) {
            copied = new CurrentStateNode();
        } else if (source instanceof NeighborCountNode count) {
            int searchState = stateCount == null
                    ? count.searchState()
                    : Math.floorMod(count.searchState(), stateCount);
            copied = new NeighborCountNode(searchState);
        } else if (source instanceof GetNeighborByDirectionNode direction) {
            copied = new GetNeighborByDirectionNode(direction.direction());
        } else if (source instanceof NeighborIsNode neighborIs) {
            int targetState = stateCount == null
                    ? neighborIs.targetState()
                    : Math.floorMod(neighborIs.targetState(), stateCount);
            copied = new NeighborIsNode(neighborIs.direction(), targetState);
        } else if (source instanceof CountSameNeighborsNode) {
            copied = new CountSameNeighborsNode();
        } else if (source instanceof IsLineNode) {
            copied = new IsLineNode();
        } else if (source instanceof CountAllNeighborsNode) {
            copied = new CountAllNeighborsNode();
        } else if (source instanceof GetPastStateNode pastState) {
            copied = new GetPastStateNode(pastState.offset());
        } else if (source instanceof SetNextStateNode setNextState) {
            copied = new SetNextStateNode(
                    copy(setNextState.valueNode(), stateCount, copies, freezeStochasticNodes));
        } else if (source instanceof ChanceNode chance) {
            copied = new ChanceNode(chance.probability());
        } else if (source instanceof RandomIntNode randomInt) {
            copied = new RandomIntNode(randomInt.min(), randomInt.max());
        } else if (source instanceof SwitchCaseNode switchCase) {
            Map<Integer, Node> copiedCases = new java.util.LinkedHashMap<>();
            for (Map.Entry<Integer, Node> entry : switchCase.cases().entrySet()) {
                copiedCases.put(entry.getKey(),
                        copy(entry.getValue(), stateCount, copies, freezeStochasticNodes));
            }
            copied = new SwitchCaseNode(
                    copy(switchCase.selector(), stateCount, copies, freezeStochasticNodes),
                    copiedCases);
            copies.put(source, copied);
        } else if (source instanceof IfNode conditional) {
            copied = new IfNode(
                    copy(conditional.condition(), stateCount, copies, freezeStochasticNodes),
                    copy(conditional.thenBranch(), stateCount, copies, freezeStochasticNodes),
                    copy(conditional.elseBranch(), stateCount, copies, freezeStochasticNodes));
        } else if (source instanceof ArithmeticNode arithmetic) {
            copied = new ArithmeticNode(
                    copy(arithmetic.left(), stateCount, copies, freezeStochasticNodes),
                    copy(arithmetic.right(), stateCount, copies, freezeStochasticNodes),
                    arithmetic.op());
        } else if (source instanceof LogicalNode logical) {
            copied = new LogicalNode(logical.logOp());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported node type: " + source.getClass().getName());
        }

        copies.put(source, copied);
        for (Node input : source.getInputs()) {
            ((NodeBase) copied).addInput(
                    copy(input, stateCount, copies, freezeStochasticNodes));
        }
        return copied;
    }
}

final class IfNode extends NodeBase {
    private final Node condition;
    private final Node thenBranch;
    private final Node elseBranch;

    IfNode(Node condition, Node thenBranch, Node elseBranch) {
        this.condition = Objects.requireNonNull(condition, "condition");
        this.thenBranch = Objects.requireNonNull(thenBranch, "thenBranch");
        this.elseBranch = Objects.requireNonNull(elseBranch, "elseBranch");
    }

    Node condition() {
        return condition;
    }

    Node thenBranch() {
        return thenBranch;
    }

    Node elseBranch() {
        return elseBranch;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            int signal = getInputs().isEmpty()
                    ? condition.evaluate(currentCell, neighbors)
                    : sumInputs(currentCell, neighbors);
            Node branch = signal > 0 ? thenBranch : elseBranch;
            return branch.evaluate(currentCell, neighbors);
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        String padding = "  ".repeat(Math.max(0, indent));
        return padding + "IF (" + NodeFormatting.inline(condition) + " > 0)\n"
                + padding + "THEN\n" + thenBranch.toPrettyString(indent + 1) + "\n"
                + padding + "ELSE\n" + elseBranch.toPrettyString(indent + 1);
    }

    @Override
    public String getName() {
        return "IF condition > 0";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs(
                "IF " + condition.toSerializedString() + " "
                        + thenBranch.toSerializedString() + " "
                        + elseBranch.toSerializedString()));
    }

    @Override
    public List<Node> getChildren() {
        return List.of(condition, thenBranch, elseBranch);
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class ArithmeticNode extends NodeBase {
    private static final String[] OPERATIONS = {"+", "-", "==", "<", ">", ">=", "<="};

    private final Node left;
    private final Node right;
    private final String op;

    ArithmeticNode(Node left, Node right, String op) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        if (!List.of(OPERATIONS).contains(op)) {
            throw new IllegalArgumentException("Unsupported operation: " + op);
        }
        this.op = op;
    }

    Node left() {
        return left;
    }

    Node right() {
        return right;
    }

    String op() {
        return op;
    }

    static String randomOperation(RandomGenerator random) {
        return OPERATIONS[random.nextInt(OPERATIONS.length)];
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            int leftValue = getInputs().isEmpty()
                    ? left.evaluate(currentCell, neighbors)
                    : sumInputs(currentCell, neighbors);
            int rightValue = right.evaluate(currentCell, neighbors);
            return switch (op) {
                case "+" -> clampToInt((long) leftValue + rightValue);
                case "-" -> clampToInt((long) leftValue - rightValue);
                case "==" -> leftValue == rightValue ? 1 : 0;
                case "<" -> leftValue < rightValue ? 1 : 0;
                case ">" -> leftValue > rightValue ? 1 : 0;
                case ">=" -> leftValue >= rightValue ? 1 : 0;
                case "<=" -> leftValue <= rightValue ? 1 : 0;
                default -> throw new IllegalStateException("Unsupported operation: " + op);
            };
        });
    }

    private static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + NodeFormatting.inline(this);
    }

    @Override
    public String getName() {
        return "Arithmetic: " + op;
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(() -> appendSerializedInputs(
                "A:" + op + " " + left.toSerializedString() + " "
                        + right.toSerializedString()));
    }

    @Override
    public List<Node> getChildren() {
        return List.of(left, right);
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class NodeFormatting {
    private NodeFormatting() {
    }

    static String inline(Node node) {
        if (node instanceof ConstantNode constant) {
            return Integer.toString(constant.val());
        }
        if (node instanceof CurrentStateNode) {
            return "SELF";
        }
        if (node instanceof NeighborCountNode count) {
            return "COUNT_NEIGHBORS(" + count.searchState() + ")";
        }
        if (node instanceof GetNeighborByDirectionNode direction) {
            return "GET_NEIGHBOR(\"" + direction.direction() + "\")";
        }
        if (node instanceof NeighborIsNode neighborIs) {
            return neighborIs.toString();
        }
        if (node instanceof CountSameNeighborsNode countSameNeighbors) {
            return countSameNeighbors.toString();
        }
        if (node instanceof IsLineNode isLine) {
            return isLine.toString();
        }
        if (node instanceof CountAllNeighborsNode) {
            return "COUNT_ALL_NEIGHBORS";
        }
        if (node instanceof GetPastStateNode pastState) {
            return "GET_PAST_STATE(" + pastState.offset() + ")";
        }
        if (node instanceof SetNextStateNode setNextState) {
            return "SET_NEXT_STATE(" + inline(setNextState.valueNode()) + ")";
        }
        if (node instanceof ChanceNode chance) {
            return "CHANCE(" + chance.probability() + "%)";
        }
        if (node instanceof RandomIntNode randomInt) {
            return "RANDOM_INT(" + randomInt.min() + ", " + randomInt.max() + ")";
        }
        if (node instanceof SwitchCaseNode switchCase) {
            StringBuilder expression = new StringBuilder("SWITCH (")
                    .append(inline(switchCase.selector())).append(") {");
            for (Map.Entry<Integer, Node> entry : switchCase.cases().entrySet()) {
                expression.append(" CASE ").append(entry.getKey())
                        .append(": ").append(inline(entry.getValue()));
            }
            return expression.append(" }").toString();
        }
        if (node instanceof ArithmeticNode arithmetic) {
            return "(" + inline(arithmetic.left()) + " " + arithmetic.op() + " "
                    + inline(arithmetic.right()) + ")";
        }
        if (node instanceof IfNode conditional) {
            return "IF (" + inline(conditional.condition()) + " > 0) THEN ("
                    + inline(conditional.thenBranch()) + ") ELSE ("
                    + inline(conditional.elseBranch()) + ")";
        }
        if (node instanceof LogicalNode logical) {
            return logical.toPrettyString(0);
        }
        throw new IllegalArgumentException("Unknown node type: " + node.getClass().getName());
    }
}

final class LogicalNode extends NodeBase {
    private static final List<String> OPERATIONS =
            List.of("AND", "OR", "XOR", "NOT", "XNOR");

    private final String logOp;

    LogicalNode(String logOp) {
        if (!OPERATIONS.contains(logOp)) {
            throw new IllegalArgumentException("Unsupported logical operation: " + logOp);
        }
        this.logOp = logOp;
    }

    String logOp() {
        return logOp;
    }

    @Override
    public int evaluate(int currentCell, int[] neighbors) {
        return evaluateWithCycleGuard(() -> {
            List<Node> inputs = getInputs();
            int leftValue = inputs.isEmpty()
                    ? 0 : inputs.get(0).evaluate(currentCell, neighbors);
            if ("NOT".equals(logOp)) {
                return leftValue > 0 ? 0 : 1;
            }

            int rightValue = inputs.size() < 2
                    ? 0 : inputs.get(1).evaluate(currentCell, neighbors);
            boolean left = leftValue > 0;
            boolean right = rightValue > 0;
            return switch (logOp) {
                case "AND" -> left && right ? 1 : 0;
                case "OR" -> left || right ? 1 : 0;
                case "XOR" -> left ^ right ? 1 : 0;
                case "XNOR" -> left == right ? 1 : 0;
                default -> throw new IllegalStateException(
                        "Unsupported logical operation: " + logOp);
            };
        });
    }

    @Override
    public Node deepCopy() {
        return NodeCopies.deepCopy(this);
    }

    @Override
    public String toPrettyString(int indent) {
        return "  ".repeat(Math.max(0, indent)) + "(" + logOp + " NODE)";
    }

    @Override
    public String toSerializedString() {
        return serializeWithCycleGuard(
                () -> appendSerializedInputs("L:" + logOp));
    }

    @Override
    public String getName() {
        return "Logical: " + logOp;
    }

    @Override
    public List<Node> getChildren() {
        return List.of();
    }

    @Override
    public Node forStateCount(int totalStates) {
        return NodeCopies.forStateCount(this, totalStates);
    }
}

final class TreeGenerator {
    private static final java.util.Random random = new java.util.Random();
    private static final String[] LOG_OPS = {"AND", "OR", "XOR", "NOT", "XNOR"};
    private static final String[] DIRECTIONS = {
        "North", "North-East", "East", "South-East",
        "South", "South-West", "West", "North-West"
    };

    static String randomLogicalOperation() {
        return LOG_OPS[random.nextInt(LOG_OPS.length)];
    }

    static String randomDirection() {
        return DIRECTIONS[random.nextInt(DIRECTIONS.length)];
    }

    private final int totalStates;

    TreeGenerator() {
        this(8);
    }

    TreeGenerator(int totalStates) {
        this.totalStates = StateCounts.checked(totalStates);
    }

    Node generate(int maxDepth) {
        return generate(maxDepth, totalStates);
    }

    Node generate(int maxDepth, int totalStates) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be non-negative");
        }
        int validatedStateCount = StateCounts.checked(totalStates);
        int dynamicDepth = random.nextInt(3, 7);
        return grow(0, dynamicDepth, validatedStateCount);
    }

    Node generateSubtree() {
        return generate(3);
    }

    private Node grow(int depth, int maxDepth, int stateCount) {
        if (depth >= maxDepth
                || (depth > 0 && random.nextDouble() < 0.35)) {
            return randomTerminal(stateCount);
        }
        double nodeChoice = random.nextDouble();
        if (nodeChoice < 0.05) {
            return new GetPastStateNode(random.nextInt(1, 3));
        }
        if (nodeChoice < 0.10) {
            return new SetNextStateNode(grow(depth + 1, maxDepth, stateCount));
        }
        nodeChoice = (nodeChoice - 0.10) / 0.90;
        if (nodeChoice < 0.15) {
            return new GetNeighborByDirectionNode(randomDirection());
        }
        if (nodeChoice < 0.25) {
            return new NeighborIsNode(randomDirection(), random.nextInt(stateCount));
        }
        if (nodeChoice < 0.35) {
            Node input = grow(depth + 1, maxDepth, stateCount);
            if (!MainApp.chanceNodesEnabled()) {
                LogicalNode logical = new LogicalNode("XOR");
                logical.addInput(input);
                return logical;
            }
            ChanceNode chance = new ChanceNode(
                    new int[] {10, 40, 75}[random.nextInt(3)]);
            chance.addInput(input);
            return chance;
        }
        if (nodeChoice < 0.45) {
            int min = random.nextInt(stateCount);
            int max = random.nextInt(min, stateCount);
            return MainApp.randomIntNodesEnabled()
                    ? new RandomIntNode(min, max)
                    : new ConstantNode(1);
        }
        if (nodeChoice < 0.55) {
            return randomSwitchCase(depth, maxDepth, stateCount);
        }
        return switch (random.nextInt(5)) {
            case 0 -> new IfNode(grow(depth + 1, maxDepth, stateCount),
                    grow(depth + 1, maxDepth, stateCount),
                    grow(depth + 1, maxDepth, stateCount));
            case 1 -> new ArithmeticNode(grow(depth + 1, maxDepth, stateCount),
                    grow(depth + 1, maxDepth, stateCount),
                    ArithmeticNode.randomOperation(random));
            case 2 -> new CountSameNeighborsNode();
            case 3 -> new IsLineNode();
            default -> randomLogicalNode(depth, maxDepth, stateCount);
        };
    }

    private Node randomSwitchCase(int depth, int maxDepth, int stateCount) {
        SwitchCaseNode switchCase = new SwitchCaseNode(new CurrentStateNode());
        int caseCount = random.nextInt(
                2, Math.min(4, stateCount) + 1);
        while (switchCase.cases().size() < caseCount) {
            int caseValue = random.nextInt(stateCount);
            switchCase.addCase(caseValue, grow(depth + 1, maxDepth, stateCount));
        }
        return switchCase;
    }

    private Node randomLogicalNode(int depth, int maxDepth, int stateCount) {
        LogicalNode logical = new LogicalNode(randomLogicalOperation());
        int inputCount = "NOT".equals(logical.logOp()) ? 1 : 2;
        for (int index = 0; index < inputCount; index++) {
            logical.addInput(grow(depth + 1, maxDepth, stateCount));
        }
        return logical;
    }

    private Node randomTerminal(int stateCount) {
        return switch (random.nextInt(7)) {
            case 0 -> new ConstantNode(random.nextInt(stateCount));
            case 1 -> new CurrentStateNode();
            case 2 -> new NeighborIsNode(randomDirection(), random.nextInt(stateCount));
            case 3 -> MainApp.randomIntNodesEnabled()
                    ? new RandomIntNode(0, stateCount - 1)
                    : new ConstantNode(1);
            case 4 -> new GetPastStateNode(random.nextInt(1, 3));
            case 5 -> new CountSameNeighborsNode();
            default -> new IsLineNode();
        };
    }

    public static Node deserialize(Queue<String> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Serialized rule has no node tokens.");
        }
        return deserializeNode(tokens, 0);
    }

    private static Node deserializeNode(Queue<String> tokens, int depth) {
        if (depth > 512) {
            throw new IllegalArgumentException("Serialized rule exceeds maximum tree depth.");
        }
        String token = tokens.poll();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Unexpected end of serialized rule.");
        }

        Node decoded;
        if ("S".equals(token)) {
            decoded = new CurrentStateNode();
        } else if (token.startsWith("C:")) {
            decoded = new ConstantNode(parseStateToken(token.substring(2), token));
        } else if (token.startsWith("N:")) {
            decoded = new NeighborCountNode(parseStateToken(token.substring(2), token));
        } else if ("IF".equals(token)) {
            Node condition = deserializeNode(tokens, depth + 1);
            Node thenBranch = deserializeNode(tokens, depth + 1);
            Node elseBranch = deserializeNode(tokens, depth + 1);
            decoded = new IfNode(condition, thenBranch, elseBranch);
        } else if (token.startsWith("A:")) {
            String operation = token.substring(2);
            Node left = deserializeNode(tokens, depth + 1);
            Node right = deserializeNode(tokens, depth + 1);
            decoded = new ArithmeticNode(left, right, operation);
        } else if (token.startsWith("D:")) {
            decoded = new GetNeighborByDirectionNode(token.substring(2));
        } else if (token.startsWith("NEIGHBOR_IS:")) {
            String[] values = token.substring("NEIGHBOR_IS:".length()).split(":", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid serialized neighbor-state test: " + token);
            }
            decoded = new NeighborIsNode(
                    values[0], parseStateToken(values[1], token));
        } else if ("COUNT_SAME".equals(token)) {
            decoded = new CountSameNeighborsNode();
        } else if ("IS_LINE".equals(token)) {
            decoded = new IsLineNode();
        } else if ("COUNT_ALL".equals(token)) {
            decoded = new CountAllNeighborsNode();
        } else if (token.startsWith("PAST:")) {
            decoded = new GetPastStateNode(parseBoundedInteger(
                    token.substring("PAST:".length()), token, 1, 2));
        } else if ("SET_NEXT".equals(token)) {
            decoded = new SetNextStateNode(deserializeNode(tokens, depth + 1));
        } else if (token.startsWith("CHANCE:")) {
            decoded = new ChanceNode(parseBoundedInteger(
                    token.substring("CHANCE:".length()), token, 0, 100));
        } else if (token.startsWith("RANDOM_INT:")) {
            String[] bounds = token.substring("RANDOM_INT:".length()).split(":", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("Invalid serialized random range: " + token);
            }
            int min = parseBoundedInteger(bounds[0], token, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int max = parseBoundedInteger(bounds[1], token, Integer.MIN_VALUE, Integer.MAX_VALUE);
            decoded = new RandomIntNode(min, max);
        } else if (token.startsWith("SWITCH:")) {
            int caseCount = parseBoundedInteger(
                    token.substring("SWITCH:".length()), token, 0, 4096);
            Node selector = deserializeNode(tokens, depth + 1);
            SwitchCaseNode switchCase = new SwitchCaseNode(selector);
            for (int index = 0; index < caseCount; index++) {
                String caseValueToken = tokens.poll();
                int caseValue = parseBoundedInteger(
                        caseValueToken, token, Integer.MIN_VALUE, Integer.MAX_VALUE);
                switchCase.addCase(caseValue, deserializeNode(tokens, depth + 1));
            }
            decoded = switchCase;
        } else if (token.startsWith("L:")) {
            decoded = new LogicalNode(token.substring(2));
        } else {
            throw new IllegalArgumentException("Unknown serialized node token: " + token);
        }

        if (!tokens.isEmpty() && tokens.peek().startsWith("IN:")) {
            String inputCountToken = tokens.poll();
            int inputCount;
            try {
                inputCount = Integer.parseInt(inputCountToken.substring(3));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Invalid serialized input count: " + inputCountToken, exception);
            }
            if (inputCount < 0 || inputCount > 4096) {
                throw new IllegalArgumentException("Serialized input count is outside 0..4096.");
            }
            for (int index = 0; index < inputCount; index++) {
                ((NodeBase) decoded).addInput(deserializeNode(tokens, depth + 1));
            }
        }
        if (!tokens.isEmpty() && "END".equals(tokens.peek())) {
            tokens.poll();
        }
        return decoded;
    }

    private static int parseStateToken(String value, String token) {
        try {
            int state = Integer.parseInt(value);
            if (state < 0 || state > 31) {
                throw new IllegalArgumentException("State token is outside 0..31: " + token);
            }
            return state;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid serialized state token: " + token,
                    exception);
        }
    }

    private static int parseBoundedInteger(
            String value, String token, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        "Serialized integer is outside its allowed range: " + token);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid serialized integer token: " + token,
                    exception);
        }
    }
}

record MutationProfile(
        double rate,
        double pointWeight,
        double subtreeWeight,
        double shrinkWeight,
        boolean leafSubtreeOnly) {
    MutationProfile {
        if (rate < 0.0 || rate > 1.0 || pointWeight < 0.0 || subtreeWeight < 0.0
                || shrinkWeight < 0.0 || pointWeight + subtreeWeight + shrinkWeight <= 0.0) {
            throw new IllegalArgumentException("Invalid mutation profile");
        }
    }
}

final class ExpressionMutator {
    private static final int SUBTREE_MAX_DEPTH = 3;
    private final TreeGenerator generator;
    private final int totalStates;

    ExpressionMutator(TreeGenerator generator, int totalStates) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.totalStates = StateCounts.checked(totalStates);
    }

    Node mutate(Node original, MutationProfile profile) {
        Node copied = original.deepCopy();
        if (ThreadLocalRandom.current().nextDouble() >= profile.rate()) {
            return copied;
        }

        double totalWeight = profile.pointWeight() + profile.subtreeWeight()
                + profile.shrinkWeight();
        double choice = ThreadLocalRandom.current().nextDouble() * totalWeight;
        if (choice < profile.shrinkWeight()) {
            return shrink(copied);
        }
        if (choice < profile.shrinkWeight() + profile.subtreeWeight()) {
            return replace(copied, chooseNode(copied, profile.leafSubtreeOnly()),
                    generator.generate(SUBTREE_MAX_DEPTH, totalStates));
        }
        return pointMutation(copied);
    }

    Node replaceSelected(Node root, Node selected, Node replacement) {
        if (!containsIdentity(root, selected)) {
            throw new IllegalArgumentException("Selected node is not part of the rule");
        }
        return replace(root, selected, replacement.deepCopy());
    }

    private Node pointMutation(Node root) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes, new IdentityHashMap<>());
        List<Node> mutable = nodes.stream()
                .filter(node -> node instanceof ConstantNode
                        || node instanceof NeighborCountNode
                        || node instanceof ArithmeticNode
                        || node instanceof LogicalNode
                        || node instanceof GetNeighborByDirectionNode
                        || node instanceof NeighborIsNode
                        || node instanceof GetPastStateNode
                        || node instanceof ChanceNode
                        || node instanceof RandomIntNode)
                .toList();
        Node target = mutable.isEmpty()
                ? nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()))
                : mutable.get(ThreadLocalRandom.current().nextInt(mutable.size()));

        Node replacement;
        if (target instanceof ConstantNode constant) {
            replacement = new ConstantNode((constant.val() + 1
                    + ThreadLocalRandom.current().nextInt(totalStates - 1)) % totalStates);
        } else if (target instanceof NeighborCountNode count) {
            replacement = new NeighborCountNode((count.searchState() + 1
                    + ThreadLocalRandom.current().nextInt(totalStates - 1)) % totalStates);
        } else if (target instanceof ArithmeticNode arithmetic) {
            String op;
            do {
                op = ArithmeticNode.randomOperation(ThreadLocalRandom.current());
            } while (op.equals(arithmetic.op()));
            replacement = new ArithmeticNode(arithmetic.left().deepCopy(),
                    arithmetic.right().deepCopy(), op);
        } else if (target instanceof LogicalNode logical) {
            String op;
            do {
                op = TreeGenerator.randomLogicalOperation();
            } while (op.equals(logical.logOp()));
            LogicalNode mutated = new LogicalNode(op);
            for (Node input : logical.getInputs()) {
                mutated.addInput(input.deepCopy());
            }
            replacement = mutated;
        } else if (target instanceof GetNeighborByDirectionNode direction) {
            String newDirection;
            do {
                newDirection = TreeGenerator.randomDirection();
            } while (newDirection.equals(direction.direction()));
            replacement = new GetNeighborByDirectionNode(newDirection);
        } else if (target instanceof NeighborIsNode neighborIs) {
            String newDirection = neighborIs.direction();
            int newTargetState = neighborIs.targetState();
            if (ThreadLocalRandom.current().nextBoolean()) {
                do {
                    newDirection = TreeGenerator.randomDirection();
                } while (newDirection.equals(neighborIs.direction()));
            } else {
                newTargetState = (neighborIs.targetState() + 1
                        + ThreadLocalRandom.current().nextInt(totalStates - 1)) % totalStates;
            }
            replacement = new NeighborIsNode(newDirection, newTargetState);
        } else if (target instanceof GetPastStateNode pastState) {
            replacement = new GetPastStateNode(pastState.offset() == 1 ? 2 : 1);
        } else if (target instanceof ChanceNode chance) {
            int probability = ThreadLocalRandom.current().nextInt(101);
            ChanceNode mutated = new ChanceNode(probability);
            for (Node input : chance.getInputs()) {
                mutated.addInput(input.deepCopy());
            }
            replacement = mutated;
        } else if (target instanceof RandomIntNode) {
            int min = ThreadLocalRandom.current().nextInt(totalStates);
            int max = ThreadLocalRandom.current().nextInt(min, totalStates);
            replacement = new RandomIntNode(min, max);
        } else {
            replacement = new ConstantNode(ThreadLocalRandom.current().nextInt(totalStates));
        }
        return replace(root, target, replacement);
    }

    private Node shrink(Node root) {
        List<Node> complex = new ArrayList<>();
        collect(root, complex, new IdentityHashMap<>());
        complex.removeIf(node -> !(node instanceof IfNode
                || node instanceof ArithmeticNode || node instanceof LogicalNode
                || node instanceof GetNeighborByDirectionNode
                || node instanceof GetPastStateNode
                || node instanceof SetNextStateNode
                || node instanceof ChanceNode || node instanceof RandomIntNode
                || node instanceof SwitchCaseNode));
        if (complex.isEmpty()) {
            return new ConstantNode(ThreadLocalRandom.current().nextInt(totalStates));
        }
        Node target = complex.get(ThreadLocalRandom.current().nextInt(complex.size()));
        return replace(root, target,
                new ConstantNode(ThreadLocalRandom.current().nextInt(totalStates)));
    }

    private Node chooseNode(Node root, boolean leavesOnly) {
        List<Node> candidates = new ArrayList<>();
        collect(root, candidates, new IdentityHashMap<>());
        if (leavesOnly) {
            candidates.removeIf(node -> !NodeTraversal.dependencies(node).isEmpty());
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private void collect(
            Node node, List<Node> nodes, IdentityHashMap<Node, Boolean> visited) {
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        nodes.add(node);
        for (Node child : NodeTraversal.dependencies(node)) {
            collect(child, nodes, visited);
        }
    }

    private boolean containsIdentity(Node root, Node target) {
        if (root == target) {
            return true;
        }
        for (Node child : NodeTraversal.dependencies(root)) {
            if (containsIdentity(child, target)) {
                return true;
            }
        }
        return false;
    }

    private Node replace(Node current, Node target, Node replacement) {
        if (current == target) {
            return replacement;
        }
        Node rebuilt;
        if (current instanceof IfNode conditional) {
            rebuilt = new IfNode(replace(conditional.condition(), target, replacement),
                    replace(conditional.thenBranch(), target, replacement),
                    replace(conditional.elseBranch(), target, replacement));
        } else if (current instanceof ArithmeticNode arithmetic) {
            rebuilt = new ArithmeticNode(replace(arithmetic.left(), target, replacement),
                    replace(arithmetic.right(), target, replacement), arithmetic.op());
        } else if (current instanceof LogicalNode logical) {
            rebuilt = new LogicalNode(logical.logOp());
        } else if (current instanceof GetNeighborByDirectionNode direction) {
            rebuilt = new GetNeighborByDirectionNode(direction.direction());
        } else if (current instanceof NeighborIsNode neighborIs) {
            rebuilt = new NeighborIsNode(neighborIs.direction(), neighborIs.targetState());
        } else if (current instanceof CountSameNeighborsNode) {
            rebuilt = new CountSameNeighborsNode();
        } else if (current instanceof IsLineNode) {
            rebuilt = new IsLineNode();
        } else if (current instanceof GetPastStateNode pastState) {
            rebuilt = new GetPastStateNode(pastState.offset());
        } else if (current instanceof SetNextStateNode setNextState) {
            rebuilt = new SetNextStateNode(
                    replace(setNextState.valueNode(), target, replacement));
        } else if (current instanceof CountAllNeighborsNode) {
            rebuilt = new CountAllNeighborsNode();
        } else if (current instanceof SwitchCaseNode switchCase) {
            SwitchCaseNode rebuiltSwitch = new SwitchCaseNode(
                    replace(switchCase.selector(), target, replacement));
            for (Map.Entry<Integer, Node> entry : switchCase.cases().entrySet()) {
                rebuiltSwitch.addCase(entry.getKey(),
                        replace(entry.getValue(), target, replacement));
            }
            rebuilt = rebuiltSwitch;
        } else {
            rebuilt = copyWithoutInputs(current);
        }
        for (Node input : current.getInputs()) {
            ((NodeBase) rebuilt).addInput(replace(input, target, replacement));
        }
        return rebuilt;
    }

    private Node copyWithoutInputs(Node node) {
        if (node instanceof ConstantNode constant) {
            return new ConstantNode(constant.val());
        }
        if (node instanceof CurrentStateNode) {
            return new CurrentStateNode();
        }
        if (node instanceof NeighborCountNode count) {
            return new NeighborCountNode(count.searchState());
        }
        if (node instanceof LogicalNode logical) {
            return new LogicalNode(logical.logOp());
        }
        if (node instanceof GetNeighborByDirectionNode direction) {
            return new GetNeighborByDirectionNode(direction.direction());
        }
        if (node instanceof NeighborIsNode neighborIs) {
            return new NeighborIsNode(neighborIs.direction(), neighborIs.targetState());
        }
        if (node instanceof CountSameNeighborsNode) {
            return new CountSameNeighborsNode();
        }
        if (node instanceof IsLineNode) {
            return new IsLineNode();
        }
        if (node instanceof CountAllNeighborsNode) {
            return new CountAllNeighborsNode();
        }
        if (node instanceof GetPastStateNode pastState) {
            return new GetPastStateNode(pastState.offset());
        }
        if (node instanceof SetNextStateNode setNextState) {
            return new SetNextStateNode(setNextState.valueNode().deepCopy());
        }
        if (node instanceof ChanceNode chance) {
            return new ChanceNode(chance.probability());
        }
        if (node instanceof RandomIntNode randomInt) {
            return new RandomIntNode(randomInt.min(), randomInt.max());
        }
        if (node instanceof SwitchCaseNode switchCase) {
            Map<Integer, Node> copiedCases = new java.util.LinkedHashMap<>();
            switchCase.cases().forEach((value, result) ->
                    copiedCases.put(value, result.deepCopy()));
            return new SwitchCaseNode(switchCase.selector().deepCopy(), copiedCases);
        }
        throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
    }
}

final class RuleChromosome {
    private static final int MAX_RULE_TABLE_SIZE = 1_048_576;
    private static final int NEIGHBOR_COUNT = 8;
    private static final ThreadLocal<int[]> CATEGORY_COUNT_SCRATCH =
            ThreadLocal.withInitial(() -> new int[32]);

    private final Node root;
    private final int totalStates;
    private final boolean stochastic;
    private final boolean temporal;
    private final int[] ruletable;
    private final int[] ruleTable;
    private final int[] categoryByState;
    private final int[] categoryRepresentatives;
    private final boolean[] directionPositions;
    private final int nonDirectionalNeighborCount;
    private final long directionCombinationCount;
    private final long compositionCount;
    // Hashlife-кэш полностью удален!

    RuleChromosome(Node root) {
        this(root, 8);
    }

    RuleChromosome(Node root, int totalStates) {
        this.totalStates = StateCounts.checked(totalStates);
        this.root = Objects.requireNonNull(root, "root").forStateCount(this.totalStates);
        this.stochastic = containsStochasticNode(this.root);
        this.temporal = containsTemporalNode(this.root);
        ruleTable = null;
        categoryByState = null;
        categoryRepresentatives = null;
        directionPositions = null;
        nonDirectionalNeighborCount = 0;
        directionCombinationCount = 0;
        compositionCount = 0;

        int requiredSize = this.totalStates * 9 * this.totalStates;
        if (!stochastic
                && !temporal
                && requiredSize <= MAX_RULE_TABLE_SIZE
                && supportsLiveCountDominantTable(this.root, this.totalStates)) {
            ruletable = compileLiveCountDominantTable(requiredSize);
        } else {
            ruletable = null;
        }
    }

    public int getNewState(int current, int[] neighbors, int totalStates) {
        return getNewState(current, neighbors, totalStates, null, -1);
    }

    int getNewState(
            int current,
            int[] neighbors,
            int totalStates,
            int[][] pastGrids,
            int cellIndex) {
        int validatedStateCount = StateCounts.checked(totalStates);
        if (temporal && pastGrids == null) {
            throw new IllegalStateException(
                    "Rule contains temporal nodes and requires past-grid context.");
        }

        if (ruletable != null
                && validatedStateCount == this.totalStates
                && current >= 0
                && current < this.totalStates
                && neighbors != null
                && neighbors.length == NEIGHBOR_COUNT) {
            int liveCount = 0;
            int dominantNeighbor = 0;
            boolean validNeighborStates = true;
            for (int neighbor : neighbors) {
                if (neighbor < 0 || neighbor >= this.totalStates) {
                    validNeighborStates = false;
                    break;
                }
                if (neighbor > 0) {
                    liveCount++;
                    if (neighbor > dominantNeighbor) {
                        dominantNeighbor = neighbor;
                    }
                }
            }
            if (validNeighborStates) {
                int index = (current * 9 + liveCount) * this.totalStates
                        + dominantNeighbor;
                return ruletable[index];
            }
        }

        // Абсолютно чистый обсчет логики нод "в лоб" без хэш-таблиц и кэша!
        java.util.function.IntSupplier evaluation = () -> Math.max(
                0, Math.min(root.evaluate(current, neighbors), validatedStateCount - 1));
        
        return pastGrids == null || !temporal
                ? evaluation.getAsInt()
                : TemporalEvaluationContext.evaluate(pastGrids, cellIndex, evaluation);
    }

    private int[] compileLiveCountDominantTable(int requiredSize) {
        int[] table = new int[requiredSize];
        int[] neighbors = new int[NEIGHBOR_COUNT];
        for (int current = 0; current < totalStates; current++) {
            for (int liveCount = 0; liveCount <= NEIGHBOR_COUNT; liveCount++) {
                for (int dominantNeighbor = 0;
                        dominantNeighbor < totalStates;
                        dominantNeighbor++) {
                    java.util.Arrays.fill(neighbors, 0);
                    if (liveCount > 0) {
                        int representative = dominantNeighbor > 0 ? dominantNeighbor : 1;
                        for (int index = 0; index < liveCount; index++) {
                            neighbors[index] = representative;
                        }
                    }
                    int result = root.evaluate(current, neighbors);
                    int tableIndex = (current * 9 + liveCount) * totalStates
                            + dominantNeighbor;
                    table[tableIndex] = Math.max(0, Math.min(result, totalStates - 1));
                }
            }
        }
        return table;
    }

    private static boolean supportsLiveCountDominantTable(Node root, int stateCount) {
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            boolean supported = node instanceof ConstantNode
                    || node instanceof CurrentStateNode
                    || node instanceof CountAllNeighborsNode
                    || node instanceof LogicalNode
                    || node instanceof ArithmeticNode
                    || node instanceof IfNode
                    || node instanceof SwitchCaseNode
                    || node instanceof NeighborCountNode count
                            && (count.searchState() == 0
                                    || stateCount == 2 && count.searchState() == 1);
            if (!supported) {
                return false;
            }
            for (Node dependency : NodeTraversal.dependencies(node)) {
                pending.push(dependency);
            }
        }
        return true;
    }

    private RuleTableFeatures collectRuleTableFeatures(Node root, int stateCount) {
        boolean[] directions = new boolean[NEIGHBOR_COUNT];
        java.util.TreeSet<Integer> queriedStates = new java.util.TreeSet<>();
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (node instanceof GetNeighborByDirectionNode direction) {
                directions[engineDirectionIndex(direction.direction())] = true;
            } else if (node instanceof NeighborCountNode count) {
                queriedStates.add(count.searchState());
            } else if (node instanceof CountAllNeighborsNode) {
                queriedStates.add(0);
            }
            for (Node dependency : NodeTraversal.dependencies(node)) {
                pending.push(dependency);
            }
        }

        queriedStates.removeIf(state -> state < 0 || state >= stateCount);
        int categoryCount = queriedStates.size() + 1;
        int[] representatives = new int[categoryCount];
        int[] stateToCategory = new int[stateCount];
        int category = 0;
        for (int state : queriedStates) {
            representatives[category] = state;
            stateToCategory[state] = category++;
        }
        int remainingRepresentative = 0;
        while (queriedStates.contains(remainingRepresentative)) {
            remainingRepresentative++;
        }
        if (remainingRepresentative >= stateCount) {
            remainingRepresentative = representatives[categoryCount - 1];
            categoryCount--;
            representatives = java.util.Arrays.copyOf(representatives, categoryCount);
        } else {
            representatives[category] = remainingRepresentative;
            for (int state = 0; state < stateCount; state++) {
                if (!queriedStates.contains(state)) {
                    stateToCategory[state] = category;
                }
            }
        }

        int directionalCount = 0;
        for (boolean direction : directions) {
            if (direction) {
                directionalCount++;
            }
        }
        int nonDirectionalCount = NEIGHBOR_COUNT - directionalCount;
        long directionCombinations = boundedPower(stateCount, directionalCount);
        long compositions = combinationCount(
                nonDirectionalCount + categoryCount - 1, categoryCount - 1);
        long entryCount = boundedMultiply(
                boundedMultiply(directionCombinations, compositions),
                stateCount);
        if (entryCount > MAX_RULE_TABLE_SIZE) {
            entryCount = -1;
        }
        return new RuleTableFeatures(
                stateToCategory,
                representatives,
                directions,
                nonDirectionalCount,
                directionCombinations,
                compositions,
                entryCount);
    }

    private int[] compileRuleTable(RuleTableFeatures features) {
        int[] table = new int[(int) features.entryCount];
        int[] categoryCounts = new int[features.categoryRepresentatives.length];
        int[] neighbors = new int[NEIGHBOR_COUNT];
        int tableIndex = 0;
        for (long composition = 0; composition < features.compositionCount; composition++) {
            decodeComposition(
                    composition,
                    features.nonDirectionalNeighborCount,
                    categoryCounts);
            fillNonDirectionalNeighbors(
                    neighbors,
                    features.directionPositions,
                    categoryCounts,
                    features.categoryRepresentatives);
            for (long directionCode = 0;
                    directionCode < features.directionCombinationCount;
                    directionCode++) {
                fillDirectionalNeighbors(
                        neighbors,
                        features.directionPositions,
                        directionCode);
                for (int current = 0; current < totalStates; current++) {
                    int result = root.evaluate(current, neighbors);
                    table[tableIndex++] = Math.max(0, Math.min(result, totalStates - 1));
                }
            }
        }
        if (tableIndex != table.length) {
            throw new IllegalStateException("Compiled rule table has an unexpected size.");
        }
        return table;
    }

    private int getRuleTableIndex(int current, int[] neighbors) {
        int[] categoryCounts = CATEGORY_COUNT_SCRATCH.get();
        java.util.Arrays.fill(categoryCounts, 0, categoryRepresentatives.length, 0);
        long directionCode = 0;
        for (int direction = 0; direction < NEIGHBOR_COUNT; direction++) {
            int neighborState = neighbors[direction];
            if (neighborState < 0 || neighborState >= totalStates) {
                return -1;
            }
            if (directionPositions[direction]) {
                directionCode = directionCode * totalStates + neighborState;
            } else {
                categoryCounts[categoryByState[neighborState]]++;
            }
        }
        long composition = rankComposition(
                categoryCounts, nonDirectionalNeighborCount);
        long index = (composition * directionCombinationCount + directionCode)
                * totalStates + current;
        return index >= 0 && index < ruleTable.length ? (int) index : -1;
    }

    private static void decodeComposition(
            long rank, int itemCount, int[] categoryCounts) {
        java.util.Arrays.fill(categoryCounts, 0);
        int remaining = itemCount;
        for (int category = 0; category < categoryCounts.length - 1; category++) {
            int value = 0;
            while (value <= remaining) {
                long groupSize = combinationCount(
                        remaining - value + categoryCounts.length - category - 2,
                        categoryCounts.length - category - 2);
                if (rank < groupSize) {
                    break;
                }
                rank -= groupSize;
                value++;
            }
            categoryCounts[category] = value;
            remaining -= value;
        }
        categoryCounts[categoryCounts.length - 1] = remaining;
    }

    private static long rankComposition(int[] categoryCounts, int itemCount) {
        long rank = 0;
        int remaining = itemCount;
        for (int category = 0; category < categoryCounts.length - 1; category++) {
            int actual = categoryCounts[category];
            for (int skipped = 0; skipped < actual; skipped++) {
                rank += combinationCount(
                        remaining - skipped + categoryCounts.length - category - 2,
                        categoryCounts.length - category - 2);
            }
            remaining -= actual;
        }
        return rank;
    }

    private static void fillNonDirectionalNeighbors(
            int[] neighbors,
            boolean[] directions,
            int[] categoryCounts,
            int[] categoryRepresentatives) {
        int category = 0;
        int remaining = categoryCounts[0];
        for (int direction = 0; direction < NEIGHBOR_COUNT; direction++) {
            if (directions[direction]) {
                continue;
            }
            while (remaining == 0 && category < categoryCounts.length - 1) {
                category++;
                remaining = categoryCounts[category];
            }
            neighbors[direction] = categoryRepresentatives[category];
            remaining--;
        }
    }

    private void fillDirectionalNeighbors(
            int[] neighbors, boolean[] directions, long directionCode) {
        for (int direction = NEIGHBOR_COUNT - 1; direction >= 0; direction--) {
            if (directions[direction]) {
                neighbors[direction] = (int) (directionCode % totalStates);
                directionCode /= totalStates;
            }
        }
    }

    private static long boundedPower(int base, int exponent) {
        long result = 1;
        for (int index = 0; index < exponent; index++) {
            result = boundedMultiply(result, base);
            if (result > MAX_RULE_TABLE_SIZE) {
                return result;
            }
        }
        return result;
    }

    private static long boundedMultiply(long left, long right) {
        if (left == 0 || right == 0) {
            return 0;
        }
        if (left > MAX_RULE_TABLE_SIZE / right) {
            return MAX_RULE_TABLE_SIZE + 1L;
        }
        return left * right;
    }

    private static long combinationCount(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        int reducedK = Math.min(k, n - k);
        long result = 1;
        for (int index = 1; index <= reducedK; index++) {
            result = result * (n - reducedK + index) / index;
            if (result > MAX_RULE_TABLE_SIZE) {
                return MAX_RULE_TABLE_SIZE + 1L;
            }
        }
        return result;
    }

    private static int engineDirectionIndex(String direction) {
        return switch (direction) {
            case "North-West" -> 0;
            case "North" -> 1;
            case "North-East" -> 2;
            case "West" -> 3;
            case "East" -> 4;
            case "South-West" -> 5;
            case "South" -> 6;
            case "South-East" -> 7;
            default -> throw new IllegalArgumentException(
                    "Unsupported neighbor direction: " + direction);
        };
    }

    private record RuleTableFeatures(
            int[] categoryByState,
            int[] categoryRepresentatives,
            boolean[] directionPositions,
            int nonDirectionalNeighborCount,
            long directionCombinationCount,
            long compositionCount,
            long entryCount) {
    }

    private static boolean containsStochasticNode(Node root) {
        return containsNodeType(root, ChanceNode.class, RandomIntNode.class);
    }

    private static boolean containsTemporalNode(Node root) {
        return containsNodeType(root, GetPastStateNode.class, SetNextStateNode.class);
    }

    private static boolean containsNodeType(Node root, Class<?>... nodeTypes) {
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            for (Class<?> nodeType : nodeTypes) {
                if (nodeType.isInstance(node)) {
                    return true;
                }
            }
            for (Node dependency : NodeTraversal.dependencies(node)) {
                pending.push(dependency);
            }
        }
        return false;
    }

    // Методы хэширования окрестности и clearHashlifeCache полностью удалены, так как они больше не нужны!


    Node getRootNode() {
        return root;
    }

    int totalStates() {
        return totalStates;
    }

    boolean isStochastic() {
        return stochastic;
    }

    boolean hasTemporalBehavior() {
        return temporal;
    }

    RuleChromosome deterministicGollySnapshot() {
        Node deterministicRoot = NodeCopies.forGollyDeterministic(root, totalStates);
        return new RuleChromosome(deterministicRoot, totalStates);
    }

    RuleChromosome deepCopy() {
        return new RuleChromosome(root.deepCopy(), totalStates);
    }

    RuleChromosome withTotalStates(int newTotalStates) {
        int validated = StateCounts.checked(newTotalStates);
        return new RuleChromosome(root, validated);
    }

    RuleChromosome mutate(MutationProfile profile) {
        TreeGenerator generator = new TreeGenerator(totalStates);
        return new RuleChromosome(
                new ExpressionMutator(generator, totalStates).mutate(root, profile),
                totalStates);
    }

    RuleChromosome replace(Node selected, Node replacement) {
        TreeGenerator generator = new TreeGenerator(totalStates);
        return new RuleChromosome(new ExpressionMutator(generator, totalStates)
                .replaceSelected(root, selected, replacement), totalStates);
    }

    String toPrettyString() {
        return root.toPrettyString(0);
    }

    public void saveToFile(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        String passport = totalStates + System.lineSeparator()
                + root.toSerializedString() + System.lineSeparator();
        Files.writeString(file.toPath(), passport, StandardCharsets.UTF_8);
    }

    public static RuleChromosome loadFromFile(File file, MainApp app) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(app, "app");

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < 2 || lines.get(0).isBlank()) {
            throw new IllegalArgumentException("Rule file must contain state count and tree data.");
        }

        final int loadedStateCount;
        try {
            loadedStateCount = StateCounts.checked(Integer.parseInt(lines.get(0).trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid totalStates value in rule file.",
                    exception);
        }

        String serializedTree = String.join(" ", lines.subList(1, lines.size())).trim();
        if (serializedTree.isEmpty()) {
            throw new IllegalArgumentException("Rule file contains no serialized tree.");
        }

        Queue<String> tokens = new ArrayDeque<>(List.of(serializedTree.split("\\s+")));
        Node loadedRoot = TreeGenerator.deserialize(tokens);
        if (!tokens.isEmpty()) {
            throw new IllegalArgumentException("Rule file contains extra node tokens.");
        }

        app.applyLoadedStateCount(loadedStateCount);
        return new RuleChromosome(loadedRoot, loadedStateCount);
    }
}

public class MainApp extends Application {
    private static CheckBox chkChance;
    private static CheckBox chkRandom;

    private static final int WINDOW_COUNT = 15;
    private static final int CHILD_COUNT = WINDOW_COUNT - 1;
    private static final int GRID_COLUMNS = 5;
    private static final int GRID_ROWS = 3;
    private static final int GRID_SIZE = 100;
    private static final int WORLD_SIZE = GRID_SIZE;
    private static final int BIG_WORLD_SIZE = 200;
    private static final double CELL_SIZE = 2.0;
    private static final int WINDOW_IMAGE_SIZE = (int) (GRID_SIZE * CELL_SIZE);
    private static final int BIG_CELL_PIXEL_SIZE = 3;
    private static final int BIG_CANVAS_PIXEL_SIZE = BIG_WORLD_SIZE * BIG_CELL_PIXEL_SIZE;
    private static final int CELL_COUNT = WORLD_SIZE * WORLD_SIZE;
    private static final double CANVAS_PIXEL_SIZE = WORLD_SIZE * CELL_SIZE;
    private static final int FILTER_SIZE = 48;
    private static final int FILTER_STEPS = 15;
    private static final int MAX_CANDIDATES_PER_CHILD = 18;
    private static final String HIDDEN_FORMULA_TEXT =
            "[КОД СКРЫТ ДЛЯ ОПТИМИЗАЦИИ FPS]";
    private static final MutationProfile VERY_BAD =
            new MutationProfile(0.60, 0.15, 0.20, 0.65, false);
    private static final MutationProfile BAD =
            new MutationProfile(0.50, 0.20, 0.20, 0.60, false);
    private static final MutationProfile NORMAL =
            new MutationProfile(0.30, 0.40, 0.40, 0.20, false);
    private static final MutationProfile GOOD =
            new MutationProfile(0.15, 0.70, 0.30, 0.0, true);
    private static final MutationProfile SUPER =
            new MutationProfile(0.10, 0.75, 0.25, 0.0, true);

    static boolean chanceNodesEnabled() {
        return chkChance == null || chkChance.isSelected();
    }

    static boolean randomIntNodesEnabled() {
        return chkRandom == null || chkRandom.isSelected();
    }

    private final GridState[] population = new GridState[WINDOW_COUNT];
    private final GridState bigViewState = new GridState(BIG_WORLD_SIZE,
            new RuleChromosome(new ConstantNode(0)));
    private final GridState[] bigViewPopulation = {bigViewState};
    private final ImageView[] populationViews = new ImageView[WINDOW_COUNT];
    private final WritableImage[] populationImages = new WritableImage[WINDOW_COUNT];
    private final int[][] populationPixelBuffers = new int[WINDOW_COUNT][];
    private final VBox[] windowPanels = new VBox[WINDOW_COUNT];
    private final Label[] windowLabels = new Label[WINDOW_COUNT];
    private final ExecutorService evolutionExecutor =
            Executors.newSingleThreadExecutor(new EvolutionThreadFactory());
    private final ExecutorService gollyExportExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "evocell-golly-export");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong evolutionRequest = new AtomicLong();

    private int selectedWindow;
    private int wildWindowIndex = 0;
    private volatile int totalStates = 8;
    private Color[] currentPalette;
    private int[] argbPalette;
    private NodeGraphContainer nodeGraphContainer;
    private ToggleButton graphToggleButton;
    private ToggleButton btnHideCode;
    private Node selectedGraphNode;
    private TabPane tabPane;
    private ImageView bigViewImageView;
    private ComboBox<String> neighborhoodCombo;
    private volatile boolean vonNeumannNeighborhood;
    private ComboBox<String> brushSelector;
    private Spinner<Integer> liveStatesSpinner;
    private TextArea formulaArea;
    private TextArea logArea;
    private Label selectedNodeLabel;
    private Label statusLabel;
    private Button startPauseButton;
    private AnimationTimer animationTimer;
    private boolean running = true;
    private long previousFrameTime;

    @Override
    public void start(Stage stage) {
        System.setProperty("prism.lcdtext", "false");
        showWelcomeScreen(stage);
    }

    private void showWelcomeScreen(Stage stage) {
        Label title = new Label("EVOCELL LAB 2.3");
        title.setStyle("-fx-text-fill: #00f5d4; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 25px; -fx-font-weight: bold;");

        Label prompt = new Label("Выберите стартовое количество состояний:");
        prompt.setStyle("-fx-text-fill: #bdcde0; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 13px;");

        Spinner<Integer> initialStatesSpinner = new Spinner<>(2, 32, 8);
        initialStatesSpinner.setEditable(true);
        initialStatesSpinner.setPrefWidth(130);

        Button launchButton = new Button("[ЗАПУСТИТЬ ЛАБОРАТОРИЮ]");
        launchButton.setPrefWidth(340);
        launchButton.setPrefHeight(48);
        launchButton.setStyle(buttonStyle("#39ff14"));
        launchButton.setOnAction(event ->
                initializeLaboratory(stage, initialStatesSpinner.getValue()));

        VBox welcomeRoot = new VBox(18, title, prompt, initialStatesSpinner, launchButton);
        welcomeRoot.setAlignment(Pos.CENTER);
        welcomeRoot.setPadding(new Insets(24));
        welcomeRoot.setStyle("-fx-background-color: #080d16;");

        stage.setTitle("EvoCell Lab 2.3");
        stage.setResizable(false);
        stage.setScene(new Scene(welcomeRoot, 400, 300));
        stage.centerOnScreen();
        stage.show();
    }

    private void initializeLaboratory(Stage stage, int initialStates) {
        totalStates = StateCounts.checked(initialStates);
        updatePalette(totalStates);
        for (int i = 0; i < WINDOW_COUNT; i++) {
            TreeGenerator generator = new TreeGenerator(totalStates);
            Node randomTree = generator.generate(4, totalStates);
            RuleChromosome independentRule = new RuleChromosome(randomTree, totalStates);
            population[i] = new GridState(WORLD_SIZE, independentRule);
            resetWorld(population[i], ThreadLocalRandom.current());
        }
        selectedWindow = 0;
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        resetWorld(bigViewState, ThreadLocalRandom.current());

        GridPane simulationGrid = createSimulationGrid();
        tabPane = createSimulationTabs(simulationGrid);
        VBox editor = createEditorPanel();
        editor.setPrefWidth(345);
        editor.setMinWidth(320);

        BorderPane workspace = new BorderPane();
        workspace.setCenter(tabPane);
        workspace.setRight(editor);
        BorderPane.setMargin(editor, new Insets(0, 0, 0, 12));

        statusLabel = new Label("Выберите окно и оцените его правило.");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #9caec3; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(3);
        logArea.setMaxHeight(80);
        logArea.setStyle("-fx-control-inner-background: #03070c; "
                + "-fx-text-fill: #8fa8c4; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 11px; -fx-border-color: #1c2b40;");

        VBox root = new VBox(6, createHeading(), createMainToolbar(), workspace,
                createRatingControls(),
                statusLabel, logArea);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #080d16;");

        Scene scene = new Scene(root, 1500, 820);
        stage.setTitle("EvoCell — 15-Window Genetic Automata Lab");
        stage.setResizable(false);
        refreshEditor();
        stage.setScene(scene);
        stage.sizeToScene();
        stage.centerOnScreen();
        stage.show();

        renderPopulation();
        renderBigView();
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!running || (previousFrameTime != 0
                        && now - previousFrameTime < 33_333_333L)) {
                    return;
                }
                previousFrameTime = now;
                if (currentPalette == null || currentPalette.length != totalStates
                        || argbPalette == null
                        || argbPalette.length != totalStates) {
                    updatePalette(totalStates);
                }
                if (tabPane.getSelectionModel().getSelectedIndex() == 0) {
                    advanceStatesSequentially(population);
                    restartExtinctWindows();
                    renderPopulation();
                } else {
                    bigViewState.rule = population[selectedWindow].rule;
                    advanceStatesSequentially(bigViewPopulation);
                    renderBigView();
                }
            }
        };
        animationTimer.start();
        appendLog("EvoCell Lab 2.3: 15 окон и Big View готовы; состояний: "
                + totalStates + ".");
    }

    private HBox createMainToolbar() {
        startPauseButton = new Button("ПАУЗА");
        startPauseButton.setPrefWidth(110);
        startPauseButton.setStyle(buttonStyle("#00f5d4"));
        startPauseButton.setOnAction(event -> toggleSimulation());

        Label statesLabel = new Label("КОЛИЧЕСТВО СОСТОЯНИЙ");
        statesLabel.setStyle("-fx-text-fill: #bdcde0; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 11px;");

        liveStatesSpinner = new Spinner<>(2, 32, totalStates);
        liveStatesSpinner.setEditable(true);
        liveStatesSpinner.setPrefWidth(90);
        liveStatesSpinner.setStyle("-fx-font-family: 'Consolas';");
        liveStatesSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue >= 2 && newValue <= 32
                    && newValue != totalStates) {
                changeTotalStates(newValue);
            }
        });

        Label neighborhoodLabel = new Label("ОКРЕСТНОСТЬ");
        neighborhoodLabel.setStyle("-fx-text-fill: #bdcde0; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 11px;");
        neighborhoodCombo = new ComboBox<>();
        neighborhoodCombo.getItems().setAll("MOORE (8)", "VON NEUMANN (4)");
        neighborhoodCombo.getSelectionModel().selectFirst();
        neighborhoodCombo.setPrefWidth(155);
        neighborhoodCombo.setStyle("-fx-font-family: 'Consolas';");
        neighborhoodCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !Objects.equals(oldValue, newValue)) {
                changeNeighborhood(newValue);
            }
        });

        HBox toolbar = new HBox(10, startPauseButton, statesLabel, liveStatesSpinner,
                neighborhoodLabel, neighborhoodCombo);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    private Label createHeading() {
        Label heading = new Label("EVOCELL  /  15-PANE TREE-BASED GENETIC PROGRAMMING");
        heading.setStyle("-fx-text-fill: #00f5d4; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 18px; -fx-font-weight: bold;");
        return heading;
    }

    private GridPane createSimulationGrid() {
        GridPane grid = new GridPane();
        grid.setSnapToPixel(true);
        grid.setHgap(4);
        grid.setVgap(4);
        grid.setAlignment(Pos.TOP_LEFT);

        for (int i = 0; i < WINDOW_COUNT; i++) {
            final int windowIndex = i;
            Label label = new Label(windowTitle(i));
            label.setStyle("-fx-text-fill: #bdcde0; -fx-font-family: 'Consolas'; "
                    + "-fx-font-size: 11px; -fx-font-weight: bold;");
            windowLabels[i] = label;

            WritableImage image = new WritableImage(WINDOW_IMAGE_SIZE, WINDOW_IMAGE_SIZE);
            int[] pixelBuffer = new int[WINDOW_IMAGE_SIZE * WINDOW_IMAGE_SIZE];
            populationImages[i] = image;
            populationPixelBuffers[i] = pixelBuffer;

            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(WINDOW_IMAGE_SIZE);
            imageView.setFitHeight(WINDOW_IMAGE_SIZE);
            imageView.setSmooth(false);
            imageView.setCache(false);
            imageView.setOnMouseClicked(event -> selectWindow(windowIndex));
            populationViews[i] = imageView;

            VBox panel = new VBox(3, label, imageView);
            panel.setSnapToPixel(true);
            panel.setAlignment(Pos.CENTER);
            panel.setPadding(new Insets(2));
            panel.setStyle(panelStyle(i == 0, i == selectedWindow));
            windowPanels[i] = panel;
            grid.add(panel, i % GRID_COLUMNS, i / GRID_COLUMNS);
        }
        return grid;
    }

    private TabPane createSimulationTabs(GridPane evolutionGrid) {
        Tab evolutionTab = new Tab("15-PANE EVOLUTION", evolutionGrid);
        evolutionTab.setClosable(false);

        Label bigViewTitle = new Label("BIG VIEW LABORATORY  /  200 × 200 CELLS");
        bigViewTitle.setStyle("-fx-text-fill: #00f5d4; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 13px; -fx-font-weight: bold;");

        Button clearButton = new Button("[ОЧИСТИТЬ ПОЛЕ]");
        clearButton.setStyle(buttonStyle("#ff6b6b"));
        clearButton.setOnAction(event -> clearBigView());

        Button btnNoise = new Button("[ЗАПОЛНИТЬ ШУМОМ]");
        btnNoise.setStyle(buttonStyle("#ffcb6b"));
        btnNoise.setOnAction(event -> fillBigViewWithNoise());

        brushSelector = new ComboBox<>();
        refreshBrushOptions(0);
        brushSelector.getSelectionModel().select(0);
        brushSelector.setPrefWidth(190);
        brushSelector.setStyle("-fx-control-inner-background: #101a2a; "
                + "-fx-text-fill: #dce8f4; -fx-font-family: 'Consolas';");

        Label brushLabel = new Label("КИСТЬ (ЦВЕТ)");
        brushLabel.setStyle("-fx-text-fill: #bdcde0; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 11px;");
        HBox tools = new HBox(10, clearButton, btnNoise, brushLabel, brushSelector);
        tools.setAlignment(Pos.CENTER_LEFT);

        bigViewImageView = new ImageView(bigViewState.image);
        bigViewImageView.setFitWidth((int) BIG_CANVAS_PIXEL_SIZE);
        bigViewImageView.setFitHeight((int) BIG_CANVAS_PIXEL_SIZE);
        bigViewImageView.setSmooth(false);
        bigViewImageView.setCache(false);
        bigViewImageView.setOnMousePressed(this::paintBigView);
        bigViewImageView.setOnMouseDragged(this::paintBigView);
        bigViewImageView.setStyle("-fx-cursor: crosshair;");

        VBox lab = new VBox(10, bigViewTitle, tools, bigViewImageView);
        lab.setSnapToPixel(true);
        lab.setPadding(new Insets(12));
        lab.setAlignment(Pos.TOP_CENTER);
        lab.setStyle("-fx-background-color: #080d16;");
        VBox.setVgrow(bigViewImageView, Priority.NEVER);

        ScrollPane bigViewScrollPane = new ScrollPane(lab);
        bigViewScrollPane.setFitToWidth(true);
        bigViewScrollPane.setPannable(true);
        bigViewScrollPane.setStyle("-fx-background: #080d16; "
                + "-fx-background-color: #080d16;");

        Tab bigViewTab = new Tab("BIG VIEW LABORATORY", bigViewScrollPane);
        bigViewTab.setClosable(false);

        TabPane tabs = new TabPane(evolutionTab, bigViewTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldTab, selectedTab) -> {
                    if (selectedTab == bigViewTab) {
                        bigViewState.rule = population[selectedWindow].rule.deepCopy();
                        renderBigView();
                    } else if (selectedTab == evolutionTab) {
                        renderPopulation();
                    }
                });
        return tabs;
    }

    private void paintBigView(javafx.scene.input.MouseEvent event) {
        int cellX = (int) (event.getX() / BIG_CELL_PIXEL_SIZE);
        int cellY = (int) (event.getY() / BIG_CELL_PIXEL_SIZE);
        if (cellX < 0 || cellX >= BIG_WORLD_SIZE || cellY < 0 || cellY >= BIG_WORLD_SIZE) {
            return;
        }
        bigViewState.current[cellY * BIG_WORLD_SIZE + cellX] = selectedBrushState();
        renderBigView();
        event.consume();
    }

    private int selectedBrushState() {
        String selected = brushSelector.getValue();
        if (selected == null || selected.isEmpty()) {
            return 0;
        }
        int separator = selected.indexOf(':');
        String index = separator < 0 ? selected : selected.substring(0, separator);
        int state = Integer.parseInt(index.trim());
        return Math.max(0, Math.min(totalStates - 1, state));
    }

    private void clearBigView() {
//        bigViewState.rule.clearHashlifeCache();
        java.util.Arrays.fill(bigViewState.current, 0);
        java.util.Arrays.fill(bigViewState.next, 0);
        renderBigView();
        statusLabel.setText("Big View очищено. Выберите кисть и нарисуйте начальную конфигурацию.");
        appendLog("Big View: поле очищено.");
    }

    private void fillBigViewWithNoise() {
//    bigViewState.rule.clearHashlifeCache();
        for (int index = 0; index < bigViewState.current.length; index++) {
            bigViewState.current[index] = ThreadLocalRandom.current().nextDouble() < 0.15
                    ? ThreadLocalRandom.current().nextInt(totalStates) : 0;
        }
        java.util.Arrays.fill(bigViewState.next, 0);
        renderBigView();
        statusLabel.setText("Big View заполнено случайным шумом; текущее правило сохранено.");
        appendLog("Big View: создан случайный шум для " + totalStates + " состояний.");
    }

    private void changeTotalStates(int newTotalStates) {
        int validated = StateCounts.checked(newTotalStates);
        if (validated == totalStates) {
            return;
        }
        evolutionRequest.incrementAndGet();
        totalStates = validated;
        updatePalette(totalStates);

        for (GridState state : population) {
            state.rule = state.rule.withTotalStates(totalStates).mutate(NORMAL);
            resetWorld(state, ThreadLocalRandom.current());
        }
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        resetWorld(bigViewState, ThreadLocalRandom.current());

        int previousBrush = selectedBrushState();
        refreshBrushOptions(Math.min(previousBrush, totalStates - 1));
        if (brushSelector != null) {
            brushSelector.getSelectionModel().select(Math.min(previousBrush, totalStates - 1));
        }
        refreshEditor();
        renderPopulation();
        renderBigView();
        statusLabel.setText("Количество состояний: " + totalStates
                + ". Палитра и правила обновлены, симуляции сброшены.");
        appendLog("Число состояний изменено на " + totalStates
                + "; палитра создана заново и все миры сброшены.");
    }

    private void changeNeighborhood(String neighborhood) {
        boolean useVonNeumann = "VON NEUMANN (4)".equals(neighborhood);
        if (vonNeumannNeighborhood == useVonNeumann) {
            return;
        }
        evolutionRequest.incrementAndGet();
        vonNeumannNeighborhood = useVonNeumann;
        for (GridState state : population) {
            resetWorld(state, ThreadLocalRandom.current());
        }
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        resetWorld(bigViewState, ThreadLocalRandom.current());
        renderPopulation();
        renderBigView();
        String neighborhoodName = useVonNeumann ? "Von Neumann (4)" : "Moore (8)";
        statusLabel.setText("Окрестность изменена на " + neighborhoodName
                + "; все миры и кэши сброшены.");
        appendLog("Окрестность изменена на " + neighborhoodName
                + "; поля сброшены и Hashlife-кэши очищены.");
    }

    void applyLoadedStateCount(int loadedStateCount) {
        int validated = StateCounts.checked(loadedStateCount);
        if (liveStatesSpinner == null) {
            throw new IllegalStateException("Laboratory state spinner is not initialized.");
        }
        if (liveStatesSpinner.getValue() != validated) {
            liveStatesSpinner.getValueFactory().setValue(validated);
        }
        if (totalStates != validated) {
            changeTotalStates(validated);
        }
    }

    private void saveSelectedRule() {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Сохранить правило EvoCell");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("EvoCell rule (*.txt)", "*.txt"));
            chooser.setInitialFileName("evocell-rule.txt");
            File file = chooser.showSaveDialog(nodeGraphContainer.getScene().getWindow());
            if (file == null) {
                return;
            }

            population[selectedWindow].rule.saveToFile(file);
            statusLabel.setText("Правило сохранено: " + file.getName());
            appendLog("Сохранено правило " + windowTitle(selectedWindow)
                    + " в файл " + file.getName() + ".");
        } catch (Exception exception) {
            exception.printStackTrace();
            showFileError("Не удалось сохранить правило", exception);
        }
    }

    private void exportSelectedRuleForGolly() {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Экспорт правила для Golly");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Golly rule (*.rule)", "*.rule"));
            chooser.setInitialFileName("MyCustomEvoRule.rule");
            File selectedFile = chooser.showSaveDialog(nodeGraphContainer.getScene().getWindow());
            if (selectedFile == null) {
                return;
            }

            String fileName = selectedFile.getName();
            String ruleName = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".rule")
                    ? fileName.substring(0, fileName.length() - ".rule".length())
                    : fileName;
            if (!ruleName.matches("[A-Za-z0-9_-]+")) {
                showFileError("Недопустимое имя правила Golly",
                        new IllegalArgumentException(
                                "Имя файла должно содержать только латинские буквы, цифры, "
                                        + "дефис или подчёркивание."));
                return;
            }

            java.nio.file.Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();
            java.nio.file.Path parent = selectedPath.getParent();
            if (parent == null) {
                throw new IOException("Не удалось определить папку для сохранения .rule.");
            }
            java.nio.file.Path outputPath = parent.resolve(ruleName + ".rule");
            File outputFile = outputPath.toFile();
            RuleChromosome chromosomeSnapshot =
                    population[selectedWindow].rule.deepCopy();
            int stateCount = totalStates;
            int[] paletteSnapshot = java.util.Arrays.copyOf(argbPalette, totalStates);

            statusLabel.setText("Строится сжатое дерево переходов Golly...");
            appendLog("Начат экспорт правила " + ruleName
                    + " в формат Golly .rule; CHANCE фиксируется по порогу > 50%, "
                    + "RANDOM_INT — по минимальному значению диапазона.");
            gollyExportExecutor.submit(() -> {
                try {
                    String ruleFileContent = generateGollyRuleFile(
                            chromosomeSnapshot, stateCount, paletteSnapshot, ruleName);
                    Files.createDirectories(parent);
                    Files.writeString(outputPath, ruleFileContent, StandardCharsets.UTF_8);
                    Platform.runLater(() -> {
                        statusLabel.setText("Правило Golly сохранено: " + outputFile.getName());
                        appendLog("Экспорт Golly завершён: " + outputFile.getName() + ".");
                    });
                } catch (Exception exception) {
                    exception.printStackTrace();
                    Platform.runLater(() ->
                            showFileError("Не удалось экспортировать правило Golly", exception));
                }
            });
        } catch (Exception exception) {
            exception.printStackTrace();
            showFileError("Не удалось экспортировать правило Golly", exception);
        }
    }

    String generateGollyTree(int totalStates) {
        int stateCount = StateCounts.checked(totalStates);
        RuleChromosome chromosome = population[selectedWindow].rule;
        if (chromosome.totalStates() != stateCount) {
            throw new IllegalArgumentException(
                    "State count does not match the selected chromosome.");
        }
        return generateGollyTable(chromosome, stateCount);
    }

    private String generateGollyTable(RuleChromosome chromosome, int totalStates) {
        int stateCount = StateCounts.checked(totalStates);
        Objects.requireNonNull(chromosome, "chromosome");
        if (chromosome.totalStates() != stateCount) {
            throw new IllegalArgumentException(
                    "State count does not match the selected chromosome.");
        }
        RuleChromosome deterministicSnapshot = chromosome.deterministicGollySnapshot();
        return new GollyTreeBuilder(deterministicSnapshot, stateCount).build();
    }

    private String generateGollyRuleFile(
            RuleChromosome chromosome,
            int totalStates,
            int[] paletteArgb,
            String ruleName) {
        if (paletteArgb.length != totalStates) {
            throw new IllegalArgumentException(
                    "Palette length does not match the rule state count.");
        }

        StringBuilder ruleFile = new StringBuilder();
        ruleFile.append("@RULE ").append(ruleName).append('\n')
                .append(generateGollyTable(chromosome, totalStates))
                .append("@COLORS\n");
        for (int state = 0; state < totalStates; state++) {
            int argb = paletteArgb[state];
            int red = (argb >>> 16) & 0xFF;
            int green = (argb >>> 8) & 0xFF;
            int blue = argb & 0xFF;
            ruleFile.append(state).append(' ')
                    .append(red).append(' ')
                    .append(green).append(' ')
                    .append(blue).append('\n');
        }
        return ruleFile.toString();
    }

    private void loadSelectedRule() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Загрузить правило EvoCell");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("EvoCell rule (*.txt)", "*.txt"));
        File file = chooser.showOpenDialog(nodeGraphContainer.getScene().getWindow());
        if (file == null) {
            return;
        }

        try {
            RuleChromosome loadedRule = RuleChromosome.loadFromFile(file, this);
            evolutionRequest.incrementAndGet();
            population[selectedWindow].rule = loadedRule;
            bigViewState.rule = loadedRule.deepCopy();
            resetWorld(population[selectedWindow], ThreadLocalRandom.current());
            resetWorld(bigViewState, ThreadLocalRandom.current());
            running = true;
            previousFrameTime = 0;
            startPauseButton.setText("ПАУЗА");
            refreshEditor();
            renderPopulation();
            renderBigView();
            statusLabel.setText("Правило загружено в " + windowTitle(selectedWindow)
                    + "; симуляция запущена.");
            appendLog("Загружено правило из " + file.getName() + " ("
                    + totalStates + " состояний).");
        } catch (IOException | IllegalArgumentException exception) {
            showFileError("Не удалось загрузить правило", exception);
        }
    }

    private void showFileError(String title, Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        statusLabel.setText(title + ": " + message);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.initOwner(nodeGraphContainer.getScene().getWindow());
        alert.showAndWait();
    }

    private void updatePalette(int totalStates) {
        int validatedStateCount = StateCounts.checked(totalStates);
        currentPalette = new Color[validatedStateCount];
        argbPalette = new int[validatedStateCount];
        currentPalette[0] = Color.web("#0f0f14");
        for (int state = 1; state < validatedStateCount; state++) {
            currentPalette[state] = Color.hsb(
                    ThreadLocalRandom.current().nextDouble() * 360.0, 1.0, 1.0);
        }
        for (int state = 0; state < validatedStateCount; state++) {
            argbPalette[state] = toArgb(currentPalette[state]);
        }
    }

    private int toArgb(Color color) {
        return (255 << 24)
                | ((int) (color.getRed() * 255.0) << 16)
                | ((int) (color.getGreen() * 255.0) << 8)
                | (int) (color.getBlue() * 255.0);
    }

    private void refreshBrushOptions(int selectedState) {
        if (brushSelector == null) {
            return;
        }
        List<String> states = new ArrayList<>(totalStates);
        for (int state = 0; state < totalStates; state++) {
            states.add(state + (state == 0 ? ": Черный космос" : ": Neon state " + state));
        }
        brushSelector.getItems().setAll(states);
        brushSelector.getSelectionModel().select(
                Math.max(0, Math.min(selectedState, totalStates - 1)));
    }

    private String windowTitle(int index) {
        return index == 0 ? "PARENT" : "CHILD " + index;
    }

    private String panelStyle(boolean parent, boolean selected) {
        String border = selected ? "#ffffff" : (parent ? "#00f5d4" : "#263951");
        String width = selected ? "2" : "1";
        return "-fx-background-color: #0d1522; -fx-background-radius: 5; "
                + "-fx-border-color: " + border + "; -fx-border-width: " + width
                + "; -fx-border-radius: 5;";
    }

    private VBox createEditorPanel() {
        Label heading = new Label("SELECTED RULE");
        heading.setStyle("-fx-text-fill: #39ff14; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 13px; -fx-font-weight: bold;");

        nodeGraphContainer = new NodeGraphContainer(this::showSelectedGraphNode);
        graphToggleButton = new ToggleButton("ГРАФ: ВКЛ");
        graphToggleButton.setMaxWidth(Double.MAX_VALUE);
        graphToggleButton.setStyle(buttonStyle("#00f5d4"));
        graphToggleButton.setOnAction(event -> {
            boolean graphEnabled = !graphToggleButton.isSelected();
            graphToggleButton.setText(
                    graphEnabled ? "ГРАФ: ВКЛ" : "ГРАФ: ВЫКЛ");
            graphToggleButton.setStyle(buttonStyle(
                    graphEnabled ? "#00f5d4" : "#ff6b6b"));
            nodeGraphContainer.setGraphRenderingEnabled(graphEnabled);
        });
        ScrollPane graphScrollPane = new ScrollPane(nodeGraphContainer);
        graphScrollPane.setVisible(false);
        graphScrollPane.setManaged(false);
        graphScrollPane.setPrefViewportWidth(315);
        graphScrollPane.setPrefViewportHeight(460);
        graphScrollPane.setPannable(true);
        graphScrollPane.setStyle("-fx-background: #080d16; "
                + "-fx-background-color: #080d16; -fx-border-color: #1c2b40;");
        graphScrollPane.viewportBoundsProperty().addListener(
                (observable, oldBounds, newBounds) -> {
                    nodeGraphContainer.setViewportSize(
                            newBounds.getWidth(), newBounds.getHeight());
                    nodeGraphContainer.setScrollPosition(
                            graphScrollPane.getHvalue(), graphScrollPane.getVvalue(),
                            graphScrollPane.getHmin(), graphScrollPane.getHmax(),
                            graphScrollPane.getVmin(), graphScrollPane.getVmax());
                });
        graphScrollPane.hvalueProperty().addListener(
                (observable, oldValue, newValue) -> nodeGraphContainer.setScrollPosition(
                        newValue.doubleValue(), graphScrollPane.getVvalue(),
                        graphScrollPane.getHmin(), graphScrollPane.getHmax(),
                        graphScrollPane.getVmin(), graphScrollPane.getVmax()));
        graphScrollPane.vvalueProperty().addListener(
                (observable, oldValue, newValue) -> nodeGraphContainer.setScrollPosition(
                        graphScrollPane.getHvalue(), newValue.doubleValue(),
                        graphScrollPane.getHmin(), graphScrollPane.getHmax(),
                        graphScrollPane.getVmin(), graphScrollPane.getVmax()));

        selectedNodeLabel = new Label("Выберите ноду графа.");
        selectedNodeLabel.setVisible(false);
        selectedNodeLabel.setManaged(false);
        selectedNodeLabel.setWrapText(true);
        selectedNodeLabel.setMinHeight(42);
        selectedNodeLabel.setStyle("-fx-text-fill: #00f5d4; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px;");

        Button randomizeButton = new Button("[РАНДОМИЗИРОВАТЬ ПРАВИЛО]");
        randomizeButton.setMaxWidth(Double.MAX_VALUE);
        randomizeButton.setStyle(buttonStyle("#ffcb6b"));
        randomizeButton.setOnAction(event -> randomizeSelectedRule());

        VBox randomizeControls = new VBox(4, randomizeButton, graphToggleButton);

        Button weaveGraphButton = new Button("[ПЕРЕПЛЕСТИ ГРАФ]");
        weaveGraphButton.setMaxWidth(Double.MAX_VALUE);
        weaveGraphButton.setStyle(buttonStyle("#00f5d4"));
        weaveGraphButton.setOnAction(event -> weaveSelectedGraph());

        Button mutateBranchButton = new Button("[МУТИРОВАТЬ ВЫБРАННУЮ ВЕТКУ]");
        mutateBranchButton.setMaxWidth(Double.MAX_VALUE);
        mutateBranchButton.setStyle(buttonStyle("#ff6b9a"));
        mutateBranchButton.setOnAction(event -> mutateSelectedBranch());

        chkChance = new CheckBox("Ноды CHANCE");
        chkRandom = new CheckBox("Ноды RANDOM_INT");
        String chkStyle = "-fx-text-fill: #00ff66; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px;";
        chkChance.setStyle(chkStyle);
        chkRandom.setStyle(chkStyle);
        chkChance.setSelected(true);
        chkRandom.setSelected(true);

        Button saveRuleButton = new Button("[СОХРАНИТЬ ПРАВИЛО]");
        saveRuleButton.setMaxWidth(Double.MAX_VALUE);
        saveRuleButton.setStyle(buttonStyle("#39ff14") + "-fx-font-size: 10px;");
        saveRuleButton.setOnAction(event -> saveSelectedRule());

        Button loadRuleButton = new Button("[ЗАГРУЗИТЬ ПРАВИЛО]");
        loadRuleButton.setMaxWidth(Double.MAX_VALUE);
        loadRuleButton.setStyle(buttonStyle("#00f5d4") + "-fx-font-size: 10px;");
        loadRuleButton.setOnAction(event -> loadSelectedRule());

        Button exportGollyButton = new Button("[ЭКСПОРТ .RULE ДЛЯ GOLLY]");
        exportGollyButton.setMaxWidth(Double.MAX_VALUE);
        exportGollyButton.setStyle(buttonStyle("#ffcb6b") + "-fx-font-size: 10px;");
        exportGollyButton.setOnAction(event -> exportSelectedRuleForGolly());

        HBox fileButtons = new HBox(6, saveRuleButton, loadRuleButton);
        HBox.setHgrow(saveRuleButton, Priority.ALWAYS);
        HBox.setHgrow(loadRuleButton, Priority.ALWAYS);

        Label formulaHeading = new Label("FORMULA");
        formulaHeading.setStyle("-fx-text-fill: #39ff14; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px; -fx-font-weight: bold;");
        btnHideCode = new ToggleButton("КОД: ПОКАЗАН");
        btnHideCode.setStyle(buttonStyle("#39ff14") + "-fx-font-size: 10px;");
        btnHideCode.setOnAction(event -> {
            boolean codeHidden = btnHideCode.isSelected();
            btnHideCode.setText(codeHidden ? "КОД: СКРЫТЬ" : "КОД: ПОКАЗАН");
            btnHideCode.setStyle(buttonStyle(codeHidden ? "#ff6b6b" : "#39ff14")
                    + "-fx-font-size: 10px;");
            if (codeHidden) {
                formulaArea.setText(HIDDEN_FORMULA_TEXT);
            } else {
                formulaArea.clear();
            }
        });
        formulaArea = new TextArea();
        formulaArea.setPrefHeight(100);
        formulaArea.setEditable(false);
        formulaArea.setWrapText(true);
        formulaArea.setStyle("-fx-control-inner-background: #03070c; "
                + "-fx-text-fill: #39ff14; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px; -fx-border-color: #16451e;");
        HBox formulaHeader = new HBox(8, formulaHeading, btnHideCode);
        formulaHeader.setAlignment(Pos.CENTER_LEFT);

        VBox editor = new VBox(9, heading, graphScrollPane, selectedNodeLabel,
                randomizeControls, weaveGraphButton, mutateBranchButton, chkChance,
                chkRandom, fileButtons, exportGollyButton,
                formulaHeader, formulaArea);
        editor.setPadding(new Insets(10));
        editor.setStyle("-fx-background-color: #0d1522; -fx-background-radius: 6; "
                + "-fx-border-color: #1c2b40; -fx-border-radius: 6;");
        VBox.setVgrow(graphScrollPane, Priority.ALWAYS);
        return editor;
    }

    private HBox createRatingControls() {
        Button veryBad = ratingButton("[СУПЕР ПЛОХО]", "#ff3158", VERY_BAD);
        Button bad = ratingButton("[ПЛОХО]", "#ff7b39", BAD);
        Button normal = ratingButton("[НОРМАЛЬНО]", "#ffd166", NORMAL);
        Button good = ratingButton("[ХОРОШО]", "#00d7a6", GOOD);
        Button superRating = ratingButton("[СУПЕР]", "#39ff14", SUPER);

        HBox controls = new HBox(8, veryBad, bad, normal, good, superRating);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(2, 0, 0, 0));
        return controls;
    }

    private Button ratingButton(String text, String accent, MutationProfile profile) {
        Button button = new Button(text);
        button.setPrefWidth(215);
        button.setPrefHeight(38);
        button.setStyle(buttonStyle(accent));
        button.setOnAction(event -> rateSelectedWindow(text, profile));
        return button;
    }

    private String buttonStyle(String accent) {
        return "-fx-background-color: #101a2a; -fx-text-fill: " + accent + "; "
                + "-fx-border-color: " + accent + "; -fx-border-radius: 3; "
                + "-fx-background-radius: 3; -fx-font-family: 'Consolas'; "
                + "-fx-font-weight: bold; -fx-cursor: hand;";
    }

    private void toggleSimulation() {
        running = !running;
        previousFrameTime = 0;
        startPauseButton.setText(running ? "ПАУЗА" : "СТАРТ");
        statusLabel.setText(running ? "Симуляция запущена." : "Симуляция на паузе.");
        if (!running) {
            updateFormulaText();
        }
    }

    private void selectWindow(int index) {
        selectedWindow = index;
        bigViewState.rule = population[index].rule.deepCopy();
        updateWindowDecoration();
        refreshEditor();
        updateFormulaText();
        statusLabel.setText("Выбрано окно " + windowTitle(index)
                + ". Оценка будет применена к этому правилу.");
        appendLog("Выбрано " + windowTitle(index) + "; правило загружено в Big View.");
        if (tabPane.getSelectionModel().getSelectedIndex() == 1) {
            renderBigView();
        }
    }

    private void updateWindowDecoration() {
        for (int i = 0; i < WINDOW_COUNT; i++) {
            windowPanels[i].setStyle(panelStyle(i == 0, i == selectedWindow));
            windowLabels[i].setText(windowTitle(i) + (i == selectedWindow ? "  ◀ SELECTED" : ""));
        }
    }

    private void refreshEditor() {
        if (nodeGraphContainer == null || formulaArea == null) {
            return;
        }
        Node rootNode = population[selectedWindow].rule.getRootNode();
        renderTreeTo2DGraph(rootNode, nodeGraphContainer, 0, 24.0, 0.0);
    }

    private void updateFormulaText() {
        if (btnHideCode.isSelected()) {
            if (!formulaArea.getText().equals(HIDDEN_FORMULA_TEXT)) {
                formulaArea.setText(HIDDEN_FORMULA_TEXT);
            }
        } else {
            formulaArea.setText(population[selectedWindow].rule.toPrettyString());
        }
    }

    public void renderTreeTo2DGraph(
            Node rootNode,
            NodeGraphContainer container,
            int level,
            double x,
            double y) {
        Objects.requireNonNull(rootNode, "rootNode");
        Objects.requireNonNull(container, "container");
        if (level < 0) {
            throw new IllegalArgumentException("level must be non-negative");
        }

        container.clearGraph();
        container.setGraphRenderingEnabled(false);
    }

    private void showSelectedGraphNode(Node node) {
        selectedGraphNode = node;
        if (selectedNodeLabel == null) {
            return;
        }
        selectedNodeLabel.setText(node == null ? "Выберите ноду графа."
                : "Узел: " + node.getName() + "\n" + node.toPrettyString(0));
    }

    private void randomizeSelectedRule() {
        evolutionRequest.incrementAndGet();
        RuleChromosome generatedRule =
                new RuleChromosome(generateRandomModuleGraph(), totalStates);
        for (int index = 0; index < WINDOW_COUNT; index++) {
            population[index].rule = generatedRule.deepCopy();
        }
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        refreshEditor();
        statusLabel.setText("Случайный граф модулей применен ко всем 15 окнам.");
        appendLog("Созданная схема модулей применена как физика ко всем 15 окнам.");
    }

    private void weaveSelectedGraph() {
        evolutionRequest.incrementAndGet();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Node root = population[selectedWindow].rule.getRootNode();
        List<Node> activeModules = collectInputModules(root);

        if (activeModules.size() < 2) {
            root = generateRandomModuleGraph();
            activeModules = collectInputModules(root);
        } else {
            root = root.deepCopy();
            activeModules = collectInputModules(root);
        }
        if (activeModules.size() < 2) {
            throw new IllegalStateException(
                    "Не удалось создать граф минимум из двух модулей.");
        }

        for (Node module : activeModules) {
            ((NodeBase) module).clearInputs();
        }

        for (Node target : activeModules) {
            if (random.nextDouble() >= 0.60) {
                continue;
            }
            int maximumLinks = Math.min(3, activeModules.size() - 1);
            int linkCount = random.nextInt(1, maximumLinks + 1);
            IdentityHashMap<Node, Boolean> selectedSources = new IdentityHashMap<>();
            while (selectedSources.size() < linkCount) {
                Node source = activeModules.get(random.nextInt(activeModules.size()));
                if (source != target && selectedSources.put(source, Boolean.TRUE) == null) {
                    ((NodeBase) target).addInput(source);
                }
            }
        }

        if (root.getInputs().isEmpty()) {
            Node source;
            do {
                source = activeModules.get(random.nextInt(activeModules.size()));
            } while (source == root);
            ((NodeBase) root).addInput(source);
        }

        List<Node> reachableModules = collectInputModules(root);
        IdentityHashMap<Node, Boolean> reachable = new IdentityHashMap<>();
        for (Node module : reachableModules) {
            reachable.put(module, Boolean.TRUE);
        }
        for (Node module : activeModules) {
            if (reachable.containsKey(module)) {
                continue;
            }
            Node target = reachableModules.get(random.nextInt(reachableModules.size()));
            ((NodeBase) target).addInput(module);
            reachable.put(module, Boolean.TRUE);
            reachableModules.add(module);
        }

        RuleChromosome rewovenRule = new RuleChromosome(root, totalStates);
        for (GridState state : population) {
            state.rule = rewovenRule.deepCopy();
        }
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        refreshEditor();
        renderPopulation();
        renderBigView();
        statusLabel.setText("Связи переплетены; обратные связи ограничены одним шагом.");
        appendLog("Граф переплетен случайными связями; цикл при повторном вычислении "
                + "ноды дает сигнал 0.");
    }

    private List<Node> collectInputModules(Node root) {
        List<Node> modules = new ArrayList<>();
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node module = pending.pop();
            if (visited.put(module, Boolean.TRUE) != null) {
                continue;
            }
            modules.add(module);
            for (Node input : module.getInputs()) {
                pending.push(input);
            }
        }
        return modules;
    }

    private Node generateRandomModuleGraph() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int moduleCount = random.nextInt(5, 8);
        List<Node> modules = new ArrayList<>(moduleCount);
        Node root = randomModule(random);
        modules.add(root);
        for (int index = 1; index < moduleCount; index++) {
            modules.add(randomModule(random));
        }

        List<int[]> possibleConnections = new ArrayList<>();
        for (int sourceIndex = 1; sourceIndex < moduleCount; sourceIndex++) {
            root.addInput(modules.get(sourceIndex));
            for (int targetIndex = 1; targetIndex < sourceIndex; targetIndex++) {
                possibleConnections.add(new int[] {sourceIndex, targetIndex});
            }
        }

        int desiredConnectionCount = random.nextInt(5, 11);
        int connectionCount = moduleCount - 1;
        while (connectionCount < desiredConnectionCount
                && !possibleConnections.isEmpty()) {
            int candidateIndex = random.nextInt(possibleConnections.size());
            int[] candidate = possibleConnections.remove(candidateIndex);
            modules.get(candidate[1]).addInput(modules.get(candidate[0]));
            connectionCount++;
        }
        return root;
    }

    private Node randomModule(ThreadLocalRandom random) {
        double nodeChoice = random.nextDouble();
        if (nodeChoice < 0.075) {
            return new GetPastStateNode(random.nextInt(1, 3));
        }
        if (nodeChoice < 0.15) {
            return new SetNextStateNode(new RandomIntNode(0, totalStates - 1));
        }
        nodeChoice = (nodeChoice - 0.15) / 0.85;
        if (nodeChoice < 0.15) {
            return new GetNeighborByDirectionNode(TreeGenerator.randomDirection());
        }
        if (nodeChoice < 0.25) {
            return new NeighborIsNode(
                    TreeGenerator.randomDirection(), random.nextInt(totalStates));
        }
        if (nodeChoice < 0.35) {
            return chanceNodesEnabled()
                    ? new ChanceNode(random.nextInt(101))
                    : new LogicalNode("XOR");
        }
        if (nodeChoice < 0.45) {
            int min = random.nextInt(totalStates);
            return randomIntNodesEnabled()
                    ? new RandomIntNode(min, random.nextInt(min, totalStates))
                    : new ConstantNode(1);
        }
        if (nodeChoice < 0.55) {
            SwitchCaseNode switchCase = new SwitchCaseNode(
                    new CurrentStateNode());
            int firstCase = random.nextInt(totalStates);
            switchCase.addCase(firstCase, new ConstantNode(random.nextInt(totalStates)));
            int secondCase = (firstCase + 1 + random.nextInt(totalStates - 1)) % totalStates;
            switchCase.addCase(secondCase, new ConstantNode(random.nextInt(totalStates)));
            return switchCase;
        }
        return switch (random.nextInt(7)) {
            case 0 -> new IfNode(
                    new NeighborIsNode(
                            TreeGenerator.randomDirection(), random.nextInt(totalStates)),
                    new ConstantNode(random.nextInt(totalStates)),
                    new ConstantNode(random.nextInt(totalStates)));
            case 1 -> new ArithmeticNode(
                    new ConstantNode(random.nextInt(totalStates)),
                    new ConstantNode(random.nextInt(totalStates)),
                    ArithmeticNode.randomOperation(random));
            case 2 -> new LogicalNode(TreeGenerator.randomLogicalOperation());
            case 3 -> new ConstantNode(random.nextInt(totalStates));
            case 4 -> new NeighborIsNode(
                    TreeGenerator.randomDirection(), random.nextInt(totalStates));
            case 5 -> new CountSameNeighborsNode();
            default -> new IsLineNode();
        };
    }

    private void mutateSelectedBranch() {
        evolutionRequest.incrementAndGet();
        Node selectedNode = selectedGraphNode;
        if (selectedNode == null) {
            statusLabel.setText("Сначала выберите ноду графа.");
            return;
        }
        Node subtree = new TreeGenerator(totalStates).generateSubtree();
        try {
            population[selectedWindow].rule =
                    population[selectedWindow].rule.replace(selectedNode, subtree);
        } catch (IllegalArgumentException exception) {
            statusLabel.setText("Не удалось заменить узел: " + exception.getMessage());
            return;
        }
        bigViewState.rule = population[selectedWindow].rule.deepCopy();
        refreshEditor();
        statusLabel.setText("Поддерево правила " + windowTitle(selectedWindow) + " заменено.");
        appendLog("Ветка правила " + windowTitle(selectedWindow) + " заменена случайным поддеревом.");
    }

    private void rateSelectedWindow(String rating, MutationProfile profile) {
        long requestId = evolutionRequest.incrementAndGet();
        promoteSelectedToParent();
        RuleChromosome parentRule = population[0].rule.deepCopy();
        updateWindowDecoration();
        refreshEditor();
        statusLabel.setText(rating + " применено к выбранному правилу; "
                + "создаются 14 мутантов с проверкой энтропии...");
        appendLog(rating + ": " + windowTitle(0)
                + " назначен PARENT; создание 14 мутантов начато.");
        evolutionExecutor.submit(() -> createChildren(parentRule, profile, requestId, rating));
    }

    private void promoteSelectedToParent() {
        if (selectedWindow != 0) {
            GridState oldParent = population[0];
            population[0] = population[selectedWindow];
            population[selectedWindow] = oldParent;
        }
        selectedWindow = 0;
        bigViewState.rule = population[0].rule.deepCopy();
    }

    private void createChildren(
            RuleChromosome parent,
            MutationProfile profile,
            long requestId,
            String rating) {
        try {
            RuleChromosome[] children = new RuleChromosome[CHILD_COUNT];
            int acceptedCount = 0;
            int fallbackCount = 0;
            for (int childIndex = 0; childIndex < CHILD_COUNT; childIndex++) {
                if (evolutionRequest.get() != requestId) {
                    return;
                }
                RuleChromosome accepted = null;
                RuleChromosome lastCandidate = parent.deepCopy();
                for (int attempt = 0; attempt < MAX_CANDIDATES_PER_CHILD; attempt++) {
                    if (evolutionRequest.get() != requestId) {
                        return;
                    }
                    lastCandidate = parent.mutate(profile);
                    if (passesEntropyFilter(
                            lastCandidate, ThreadLocalRandom.current(), requestId)) {
                        accepted = lastCandidate;
                        break;
                    }
                }
                if (accepted == null) {
                    children[childIndex] = lastCandidate;
                    fallbackCount++;
                } else {
                    children[childIndex] = accepted;
                    acceptedCount++;
                }
            }

            if (evolutionRequest.get() != requestId) {
                return;
            }
            int passed = acceptedCount;
            int fallback = fallbackCount;
            Platform.runLater(() -> {
                if (evolutionRequest.get() != requestId) {
                    return;
                }
                for (int i = 0; i < CHILD_COUNT; i++) {
                    population[i + 1].rule = children[i];
                    resetWorld(population[i + 1], ThreadLocalRandom.current());
                }
                selectedWindow = 0;
                bigViewState.rule = population[0].rule.deepCopy();
                updateWindowDecoration();
                refreshEditor();
                renderPopulation();
                renderBigView();
                statusLabel.setText(rating + ": 14 потомков готовы; "
                        + passed + " прошли фильтр энтропии, " + fallback
                        + " не прошли лимит попыток.");
                appendLog(rating + ": потомки готовы. Фильтр пройден: "
                        + passed + "/14; fallback: " + fallback + ".");
            });
        } catch (RuntimeException exception) {
            Platform.runLater(() -> {
                if (evolutionRequest.get() == requestId) {
                    statusLabel.setText("Ошибка генерации потомков: " + exception.getMessage());
                }
            });
        }
    }

    private boolean passesEntropyFilter(
            RuleChromosome rule,
            RandomGenerator random,
            long requestId) {
        GridState virtualWorld = new GridState(FILTER_SIZE, rule);
        randomizeWorld(virtualWorld.current, random);
        for (int step = 0; step < FILTER_STEPS; step++) {
            if (evolutionRequest.get() != requestId) {
                return false;
            }
            advanceOneGeneration(virtualWorld);
        }

        int stateCount = rule.totalStates();
        boolean[] seen = new boolean[stateCount];
        int distinctStates = 0;
        for (int state : virtualWorld.current) {
            if (!seen[state]) {
                seen[state] = true;
                distinctStates++;
            }
        }
        return distinctStates > 1 && distinctStates < stateCount;
    }

    private void getNeighborsArray(
            int[] cells, int x, int y, int size, int[] neighbors) {
        if (neighbors.length != 8) {
            throw new IllegalArgumentException("Neighbor buffer must contain exactly 8 entries.");
        }
        int left = (x - 1 + size) % size;
        int right = (x + 1) % size;
        int above = (y - 1 + size) % size;
        int below = (y + 1) % size;
        if (vonNeumannNeighborhood) {
            neighbors[0] = 0;
            neighbors[1] = cells[above * size + x];
            neighbors[2] = 0;
            neighbors[3] = cells[y * size + left];
            neighbors[4] = cells[y * size + right];
            neighbors[5] = 0;
            neighbors[6] = cells[below * size + x];
            neighbors[7] = 0;
            return;
        }
        neighbors[0] = cells[above * size + left];
        neighbors[1] = cells[above * size + x];
        neighbors[2] = cells[above * size + right];
        neighbors[3] = cells[y * size + left];
        neighbors[4] = cells[y * size + right];
        neighbors[5] = cells[below * size + left];
        neighbors[6] = cells[below * size + x];
        neighbors[7] = cells[below * size + right];
    }

    private void advanceStatesSequentially(GridState[] states) {
        for (GridState state : states) {
            calculateNextGeneration(state);
        }
        for (GridState state : states) {
            rotateGeneration(state);
        }
    }

    private void fastForwardWorld(GridState state, int steps) {
        for (int step = 0; step < steps; step++) {
            advanceOneGeneration(state);
        }
    }

    private void advanceOneGeneration(GridState state) {
        calculateNextGeneration(state);
        rotateGeneration(state);
    }

    private void calculateNextGeneration(GridState state) {
        int size = state.size;
        int[] neighbors = new int[8];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                getNeighborsArray(state.current, x, y, size, neighbors);
                int cellIndex = y * size + x;
                state.next[cellIndex] = state.rule.getNewState(
                        state.current[cellIndex],
                        neighbors,
                        state.rule.totalStates(),
                        state.past,
                        cellIndex);
            }
        }
    }

    private void rotateGeneration(GridState state) {
        int[] reusableBuffer = state.past[1];
        state.past[1] = state.past[0];
        state.past[0] = state.current;
        state.current = state.next;
        state.next = reusableBuffer;
        java.util.Arrays.fill(state.next, 0);
    }

    private void randomizeWorld(int[] cells, RandomGenerator random) {
        for (int index = 0; index < cells.length; index++) {
            cells[index] = random.nextDouble() < 0.15
                    ? 1 + random.nextInt(totalStates - 1) : 0;
        }
    }

    private void restartExtinctWindows() {
        for (int windowIndex = 0; windowIndex < WINDOW_COUNT; windowIndex++) {
            GridState state = population[windowIndex];
            boolean[] seenStates = new boolean[totalStates];
            int uniqueStateCount = 0;
            for (int cellState : state.current) {
                if (cellState >= 0 && cellState < seenStates.length
                        && !seenStates[cellState]) {
                    seenStates[cellState] = true;
                    uniqueStateCount++;
                    if (uniqueStateCount > 1) {
                        break;
                    }
                }
            }

            if (uniqueStateCount != 1) {
                continue;
            }

            if (windowIndex == wildWindowIndex) {
                state.rule = new RuleChromosome(generateRandomModuleGraph(), totalStates);
                if (windowIndex == selectedWindow) {
                    bigViewState.rule = state.rule.deepCopy();
                    refreshEditor();
                }
                wildWindowIndex = new java.util.Random().nextInt(WINDOW_COUNT);
                appendLog("Дикое окно " + windowTitle(windowIndex)
                        + " вымерло: создано новое случайное правило. Следующее дикое окно: "
                        + windowTitle(wildWindowIndex) + ".");
            } else {
                appendLog("Окно " + windowTitle(windowIndex)
                        + " вымерло: правило сохранено, поле перезаполнено шумом.");
            }

            fillWorldWithNoise(state);
        }
    }

    private void fillWorldWithNoise(GridState state) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int cellIndex = 0; cellIndex < state.current.length; cellIndex++) {
            state.current[cellIndex] = random.nextInt(totalStates);
        }
        java.util.Arrays.fill(state.next, 0);
        for (int[] pastGeneration : state.past) {
            java.util.Arrays.fill(pastGeneration, 0);
        }
    }

    private void resetWorld(GridState state, RandomGenerator random) {
//        state.rule.clearHashlifeCache();
        randomizeWorld(state.current, random);
        java.util.Arrays.fill(state.next, 0);
        for (int[] pastGeneration : state.past) {
            java.util.Arrays.fill(pastGeneration, 0);
        }
    }

    private void renderPopulation() {
        for (int i = 0; i < WINDOW_COUNT; i++) {
            renderWindowState(population[i], populationImages[i], populationPixelBuffers[i]);
        }
    }

    private void renderBigView() {
        if (bigViewImageView == null) {
            return;
        }
        renderState(bigViewState);
    }

    private void renderState(GridState state) {
        for (int cell = 0; cell < state.current.length; cell++) {
            state.pixelBuffer[cell] = argbPalette[state.current[cell]];
        }
        PixelWriter writer = state.image.getPixelWriter();
        writer.setPixels(0, 0, state.size, state.size,
                PixelFormat.getIntArgbInstance(), state.pixelBuffer, 0, state.size);
    }

    private void renderWindowState(
            GridState state, WritableImage image, int[] pixelBuffer) {
        int imageSize = WINDOW_IMAGE_SIZE;
        int scale = imageSize / state.size;
        for (int y = 0; y < imageSize; y++) {
            int sourceY = y / scale;
            int sourceRowOffset = sourceY * state.size;
            int targetRowOffset = y * imageSize;
            for (int x = 0; x < imageSize; x++) {
                int sourceX = x / scale;
                int stateValue = state.current[sourceRowOffset + sourceX];
                pixelBuffer[targetRowOffset + x] = argbPalette[stateValue];
            }
        }
        PixelWriter writer = image.getPixelWriter();
        writer.setPixels(
                0,
                0,
                imageSize,
                imageSize,
                PixelFormat.getIntArgbInstance(),
                pixelBuffer,
                0,
                imageSize);
    }

    private static final class GollyTreeBuilder {
        private static final int GOLLY_STATE_COUNT = 32;
        private static final int NEIGHBOR_COUNT = 8;
        private static final int TREE_DEPTH = NEIGHBOR_COUNT + 1;
        private static final int[] GOLLY_TO_ENGINE_NEIGHBOR = {0, 2, 5, 7, 1, 3, 4, 6};

        private final RuleChromosome chromosome;
        private final int stateCount;
        private final boolean[] directionPositions = new boolean[NEIGHBOR_COUNT];
        private final List<java.util.BitSet> categories = new ArrayList<>();
        private final int[] categoryRepresentatives;
        private final int[] categoryByState = new int[GOLLY_STATE_COUNT];
        private final Map<TreeDecisionKey, Integer> decisionMemo = new java.util.HashMap<>();
        private final Map<TreeNodeKey, Integer> uniqueNodes = new java.util.HashMap<>();
        private final List<TreeRecord> nodes = new ArrayList<>();

        private GollyTreeBuilder(RuleChromosome chromosome, int stateCount) {
            this.chromosome = Objects.requireNonNull(chromosome, "chromosome");
            this.stateCount = StateCounts.checked(stateCount);
            if (chromosome.totalStates() != this.stateCount) {
                throw new IllegalArgumentException(
                        "State count does not match the exported chromosome.");
            }
            if (chromosome.hasTemporalBehavior() || chromosome.isStochastic()) {
                throw new IllegalArgumentException(
                        "Golly @TREE builder requires a deterministic, non-temporal rule.");
            }
            collectDirectionPositions(chromosome.getRootNode(), new IdentityHashMap<>());
            this.categoryRepresentatives = createCategories(chromosome.getRootNode());
        }

        private String build() {
            int[] assignedDirections = new int[NEIGHBOR_COUNT];
            java.util.Arrays.fill(assignedDirections, -1);
            int rootId = buildNode(0, new int[categories.size()], assignedDirections);
            TreeRecord root = nodes.get(rootId);
            if (root.depth != TREE_DEPTH || rootId != nodes.size() - 1) {
                throw new IllegalStateException("Generated Golly tree has an invalid root node.");
            }

            StringBuilder tree = new StringBuilder();
            tree.append("@TREE\n")
                    .append("num_states=").append(GOLLY_STATE_COUNT).append('\n')
                    .append("num_neighbors=").append(NEIGHBOR_COUNT).append('\n')
                    .append("num_nodes=").append(nodes.size()).append('\n');
            for (TreeRecord node : nodes) {
                tree.append(node.depth);
                for (int child : node.children) {
                    tree.append(' ').append(child);
                }
                tree.append('\n');
            }
            return tree.toString();
        }

        private int[] createCategories(Node root) {
            java.util.TreeSet<Integer> queriedStates = new java.util.TreeSet<>();
            collectQueriedStates(root, queriedStates, new IdentityHashMap<>());
            queriedStates.removeIf(state -> state < 0 || state >= GOLLY_STATE_COUNT);

            java.util.BitSet remaining = new java.util.BitSet(GOLLY_STATE_COUNT);
            remaining.set(0, GOLLY_STATE_COUNT);
            for (int state : queriedStates) {
                java.util.BitSet singleton = new java.util.BitSet(GOLLY_STATE_COUNT);
                singleton.set(state);
                categories.add(singleton);
                remaining.clear(state);
            }
            if (!remaining.isEmpty()) {
                categories.add(remaining);
            }
            if (categories.isEmpty()) {
                java.util.BitSet allStates = new java.util.BitSet(GOLLY_STATE_COUNT);
                allStates.set(0, GOLLY_STATE_COUNT);
                categories.add(allStates);
            }

            int[] representatives = new int[categories.size()];
            for (int category = 0; category < categories.size(); category++) {
                java.util.BitSet states = categories.get(category);
                representatives[category] = states.nextSetBit(0);
                for (int state = states.nextSetBit(0);
                        state >= 0;
                        state = states.nextSetBit(state + 1)) {
                    categoryByState[state] = category;
                }
            }
            return representatives;
        }

        private void collectDirectionPositions(
                Node node, IdentityHashMap<Node, Boolean> visited) {
            if (visited.put(node, Boolean.TRUE) != null) {
                return;
            }
            if (node instanceof GetNeighborByDirectionNode direction) {
                directionPositions[engineDirectionIndex(direction.direction())] = true;
            } else if (node instanceof NeighborIsNode neighborIs) {
                directionPositions[engineDirectionIndex(neighborIs.direction())] = true;
            } else if (node instanceof CountSameNeighborsNode
                    || node instanceof IsLineNode) {
                directionPositions[1] = true;
                directionPositions[3] = true;
                directionPositions[4] = true;
                directionPositions[6] = true;
            }
            for (Node dependency : NodeTraversal.dependencies(node)) {
                collectDirectionPositions(dependency, visited);
            }
        }

        private int engineDirectionIndex(String direction) {
            return switch (direction) {
                case "North-West" -> 0;
                case "North" -> 1;
                case "North-East" -> 2;
                case "West" -> 3;
                case "East" -> 4;
                case "South-West" -> 5;
                case "South" -> 6;
                case "South-East" -> 7;
                default -> throw new IllegalArgumentException(
                        "Unsupported neighbor direction: " + direction);
            };
        }

        private void collectQueriedStates(
                Node node,
                java.util.Set<Integer> queriedStates,
                IdentityHashMap<Node, Boolean> visited) {
            if (visited.put(node, Boolean.TRUE) != null) {
                return;
            }
            if (node instanceof NeighborCountNode count) {
                queriedStates.add(count.searchState());
            } else if (node instanceof CountAllNeighborsNode) {
                queriedStates.add(0);
            }
            for (Node dependency : NodeTraversal.dependencies(node)) {
                collectQueriedStates(dependency, queriedStates, visited);
            }
        }

        private int buildNode(
                int neighborPosition, int[] counts, int[] assignedDirections) {
            TreeDecisionKey decisionKey =
                    new TreeDecisionKey(neighborPosition, counts, assignedDirections);
            Integer cachedId = decisionMemo.get(decisionKey);
            if (cachedId != null) {
                return cachedId;
            }

            int nodeId;
            if (neighborPosition == NEIGHBOR_COUNT) {
                int[] engineNeighbors = createRepresentativeNeighbors(
                        counts, assignedDirections);
                int[] outputsByCenterState = new int[GOLLY_STATE_COUNT];
                for (int centerState = 0;
                        centerState < GOLLY_STATE_COUNT;
                        centerState++) {
                    outputsByCenterState[centerState] = chromosome.getNewState(
                            centerState, engineNeighbors, chromosome.totalStates());
                }
                nodeId = internNode(1, outputsByCenterState);
            } else {
                int[] children = new int[GOLLY_STATE_COUNT];
                int engineIndex = GOLLY_TO_ENGINE_NEIGHBOR[neighborPosition];
                if (directionPositions[engineIndex]) {
                    for (int state = 0; state < GOLLY_STATE_COUNT; state++) {
                        assignedDirections[engineIndex] = state;
                        children[state] = buildNode(
                                neighborPosition + 1, counts, assignedDirections);
                    }
                    assignedDirections[engineIndex] = -1;
                } else {
                    for (int category = 0; category < categories.size(); category++) {
                        counts[category]++;
                        int child = buildNode(
                                neighborPosition + 1, counts, assignedDirections);
                        counts[category]--;
                        for (int state = categories.get(category).nextSetBit(0);
                                state >= 0;
                                state = categories.get(category).nextSetBit(state + 1)) {
                            children[state] = child;
                        }
                    }
                }
                nodeId = internNode(TREE_DEPTH - neighborPosition, children);
            }

            decisionMemo.put(decisionKey, nodeId);
            return nodeId;
        }

        private int[] createRepresentativeNeighbors(
                int[] counts, int[] assignedDirections) {
            int[] neighbors = new int[NEIGHBOR_COUNT];
            for (int direction = 0; direction < NEIGHBOR_COUNT; direction++) {
                if (directionPositions[direction]) {
                    if (assignedDirections[direction] < 0) {
                        throw new IllegalStateException(
                                "Directional neighbor was not assigned in the tree branch.");
                    }
                    neighbors[direction] = assignedDirections[direction];
                }
            }

            int position = 0;
            for (int category = 0; category < counts.length; category++) {
                for (int occurrence = 0; occurrence < counts[category]; occurrence++) {
                    while (position < NEIGHBOR_COUNT
                            && directionPositions[GOLLY_TO_ENGINE_NEIGHBOR[position]]) {
                        position++;
                    }
                    if (position >= NEIGHBOR_COUNT) {
                        throw new IllegalStateException(
                                "Too many category values for non-directional neighbors.");
                    }
                    neighbors[GOLLY_TO_ENGINE_NEIGHBOR[position++]] = 
                            categoryRepresentatives[category];
                }
            }
            while (position < NEIGHBOR_COUNT
                    && directionPositions[GOLLY_TO_ENGINE_NEIGHBOR[position]]) {
                position++;
            }
            if (position != NEIGHBOR_COUNT) {
                throw new IllegalStateException("Golly tree leaf has an incomplete neighborhood.");
            }
            return neighbors;
        }

        private int internNode(int depth, int[] children) {
            TreeNodeKey key = new TreeNodeKey(depth, children);
            Integer existingId = uniqueNodes.get(key);
            if (existingId != null) {
                return existingId;
            }

            int nodeId = nodes.size();
            nodes.add(new TreeRecord(depth, children.clone()));
            uniqueNodes.put(key, nodeId);
            return nodeId;
        }

        private static final class TreeDecisionKey {
            private final int position;
            private final byte[] counts;
            private final byte[] assignedDirections;
            private final int hashCode;

            private TreeDecisionKey(
                    int position, int[] counts, int[] assignedDirections) {
                this.position = position;
                this.counts = new byte[counts.length];
                for (int index = 0; index < counts.length; index++) {
                    this.counts[index] = (byte) counts[index];
                }
                this.assignedDirections = new byte[assignedDirections.length];
                for (int index = 0; index < assignedDirections.length; index++) {
                    this.assignedDirections[index] = (byte) assignedDirections[index];
                }
                this.hashCode = 31 * (31 * position
                        + java.util.Arrays.hashCode(this.counts))
                        + java.util.Arrays.hashCode(this.assignedDirections);
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof TreeDecisionKey key
                        && position == key.position
                        && java.util.Arrays.equals(counts, key.counts)
                        && java.util.Arrays.equals(
                                assignedDirections, key.assignedDirections);
            }

            @Override
            public int hashCode() {
                return hashCode;
            }
        }

        private static final class TreeNodeKey {
            private final int depth;
            private final int[] children;
            private final int hashCode;

            private TreeNodeKey(int depth, int[] children) {
                this.depth = depth;
                this.children = children.clone();
                this.hashCode = 31 * depth + java.util.Arrays.hashCode(this.children);
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof TreeNodeKey key
                        && depth == key.depth
                        && java.util.Arrays.equals(children, key.children);
            }

            @Override
            public int hashCode() {
                return hashCode;
            }
        }

        private static final class TreeRecord {
            private final int depth;
            private final int[] children;

            private TreeRecord(int depth, int[] children) {
                this.depth = depth;
                this.children = children;
            }
        }
    }

    private void appendLog(String message) {
        if (logArea == null) {
            return;
        }
        String[] lines = logArea.getText().split("\\R");
        StringBuilder updated = new StringBuilder();
        int firstLine = Math.max(0, lines.length - 79);
        for (int i = firstLine; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                updated.append(lines[i]).append('\n');
            }
        }
        updated.append(message);
        logArea.setText(updated.toString());
        logArea.positionCaret(logArea.getLength());
    }

    @Override
    public void stop() {
        evolutionRequest.incrementAndGet();
        if (animationTimer != null) {
            animationTimer.stop();
        }
        evolutionExecutor.shutdownNow();
        gollyExportExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static final class GridState {
        private final int size;
        private RuleChromosome rule;
        private int[] current;
        private int[] next;
        private final int[][] past;
        private final int[] pixelBuffer;
        private final WritableImage image;

        private GridState(int size, RuleChromosome rule) {
            this.size = size;
            this.rule = rule;
            this.current = new int[size * size];
            this.next = new int[size * size];
            this.past = new int[][] {
                new int[size * size],
                new int[size * size]
            };
            this.pixelBuffer = new int[size * size];
            this.image = new WritableImage(size, size);
        }
    }

    private static final class EvolutionThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "evocell-population-evolution");
            thread.setDaemon(true);
            return thread;
        }
    }

}

final class VisualNode extends StackPane {
    static final double NODE_SIZE = 60.0;

    private final Node model;
    private final NodeGraphContainer graph;
    private double deltaX;
    private double deltaY;

    VisualNode(Node model, NodeGraphContainer graph, Consumer<Node> selectionHandler) {
        this.model = Objects.requireNonNull(model, "model");
        this.graph = Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(selectionHandler, "selectionHandler");

        setPrefSize(NODE_SIZE, NODE_SIZE);
        setMinSize(NODE_SIZE, NODE_SIZE);
        setMaxSize(NODE_SIZE, NODE_SIZE);
        setManaged(false);
        setPickOnBounds(true);
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: rgba(30, 30, 35, 0.9); "
                + "-fx-border-color: #5a5a6a; "
                + "-fx-border-width: 2.5px; "
                + "-fx-border-radius: 6px; "
                + "-fx-background-radius: 6px; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 10, 0, 0, 4);");

        Label name = new Label(displayName(model));
        name.setAlignment(Pos.CENTER);
        name.setFont(Font.font("Consolas", FontWeight.BOLD, 18));
        name.setTextFill(Color.rgb(240, 240, 245));
        name.setMouseTransparent(true);
        getChildren().add(name);

        updateStyle();

        setOnMousePressed(event -> {
            deltaX = event.getSceneX() - getLayoutX();
            deltaY = event.getSceneY() - getLayoutY();
            graph.selectNode(this.model);
            event.consume();
        });
        setOnMouseDragged(event -> {
            setLayoutX(event.getSceneX() - deltaX);
            setLayoutY(event.getSceneY() - deltaY);
            graph.nodeMoved(this);
            event.consume();
        });
        setOnMouseClicked(event -> {
            graph.selectNode(this.model);
            event.consume();
        });
    }

    Node model() {
        return model;
    }

    void setSelected(boolean selected) {
        updateStyle();
    }

    private void updateStyle() {
        String borderColor = "#5a5a6a";
        if (model instanceof IfNode) {
            borderColor = "#ff0055";
        } else if (model instanceof NeighborCountNode) {
            borderColor = "#00ff66";
        } else if (model instanceof ArithmeticNode) {
            borderColor = "#0099ff";
        } else if (model instanceof ConstantNode) {
            borderColor = "#ffcc00";
        } else if (model instanceof CurrentStateNode) {
            borderColor = "#a8b7ff";
        }

        setStyle("-fx-background-color: rgba(30, 30, 35, 0.9); "
                + "-fx-border-color: " + borderColor + "; "
                + "-fx-border-width: 2.5px; "
                + "-fx-border-radius: 6px; "
                + "-fx-background-radius: 6px; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 10, 0, 0, 4);");
    }

    private static String displayName(Node node) {
        if (node instanceof ConstantNode constant) {
            return Integer.toString(constant.val());
        }
        if (node instanceof IfNode) {
            return "IF";
        }
        if (node instanceof ArithmeticNode) {
            return "A";
        }
        if (node instanceof NeighborCountNode) {
            return "N";
        }
        if (node instanceof NeighborIsNode) {
            return "IS";
        }
        if (node instanceof CountSameNeighborsNode) {
            return "SAME";
        }
        if (node instanceof IsLineNode) {
            return "LINE";
        }
        if (node instanceof CurrentStateNode) {
            return "S";
        }
        return "NODE";
    }
}

final class NodeGraphContainer extends Pane {
    static final double LEVEL_SPACING = 100.0;
    static final double VERTICAL_SPACING = 88.0;

    private final Canvas connectionCanvas = new Canvas(560.0, 560.0);
    private final Map<Node, VisualNode> visualByModel = new IdentityHashMap<>();
    private final List<GraphConnection> connections = new ArrayList<>();
    private final Consumer<Node> selectionHandler;
    private double contentWidth = 560.0;
    private double contentHeight = 560.0;
    private boolean graphRenderingEnabled = true;

    NodeGraphContainer(Consumer<Node> selectionHandler) {
        this.selectionHandler = Objects.requireNonNull(selectionHandler, "selectionHandler");
        connectionCanvas.setManaged(false);
        connectionCanvas.setMouseTransparent(true);
        getChildren().add(connectionCanvas);
        setStyle("-fx-background-color: #080d16;");
        setMinSize(contentWidth, contentHeight);
        setPrefSize(contentWidth, contentHeight);
    }

    void clearGraph() {
        visualByModel.clear();
        connections.clear();
        getChildren().setAll(connectionCanvas);
        redrawConnections();
    }

    void setGraphRenderingEnabled(boolean enabled) {
        graphRenderingEnabled = enabled;
        setVisible(enabled);
        if (enabled) {
            redrawConnections();
        } else {
            GraphicsContext graphics = connectionCanvas.getGraphicsContext2D();
            graphics.clearRect(0.0, 0.0, connectionCanvas.getWidth(),
                    connectionCanvas.getHeight());
        }
    }

    void setGraphSize(double width, double height) {
        contentWidth = Math.max(560.0, width);
        contentHeight = Math.max(560.0, height);
        setMinSize(contentWidth, contentHeight);
        setPrefSize(contentWidth, contentHeight);
        updateCanvasSize();
        redrawConnections();
    }

    void setViewportSize(double width, double height) {
        if (width > contentWidth || height > contentHeight) {
            setGraphSize(Math.max(width, contentWidth), Math.max(height, contentHeight));
        }
        redrawConnections();
    }

    void setScrollPosition(
            double horizontalValue,
            double verticalValue,
            double horizontalMin,
            double horizontalMax,
            double verticalMin,
            double verticalMax) {
        redrawConnections();
    }

    VisualNode addVisualNode(
            Node model,
            double x,
            double centerY,
            Consumer<Node> nodeSelectionHandler) {
        VisualNode existing = visualByModel.get(model);
        if (existing != null) {
            return existing;
        }
        VisualNode visual = new VisualNode(model, this, nodeSelectionHandler);
        visual.setLayoutX(x);
        visual.setLayoutY(Math.max(8.0, centerY - VisualNode.NODE_SIZE / 2.0));
        visualByModel.put(model, visual);
        resizeForNode(visual);
        redrawConnections();
        return visual;
    }

    void connect(VisualNode source, VisualNode target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        target.model().addInput(source.model());
        drawConnection(source, target);
    }

    void drawConnection(VisualNode source, VisualNode target) {
        if (source == null || target == null || source == target) {
            return;
        }
        boolean exists = connections.stream().anyMatch(connection ->
                connection.source() == source && connection.target() == target);
        if (!exists) {
            connections.add(new GraphConnection(source, target));
            redrawConnections();
        }
    }

    VisualNode visualFor(Node model) {
        return visualByModel.get(model);
    }

    void selectNode(Node model) {
        for (Map.Entry<Node, VisualNode> entry : visualByModel.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == model);
        }
        selectionHandler.accept(model);
    }

    void nodeMoved(VisualNode node) {
        resizeForNode(node);
        requestLayout();
        redrawConnections();
    }

    @Override
    protected void layoutChildren() {
        updateCanvasSize();
        redrawConnections();
    }

    private void resizeForNode(VisualNode node) {
        double requiredWidth = node.getLayoutX() + VisualNode.NODE_SIZE + 50.0;
        double requiredHeight = node.getLayoutY() + VisualNode.NODE_SIZE + 50.0;
        if (requiredWidth > contentWidth || requiredHeight > contentHeight) {
            setGraphSize(Math.max(contentWidth, requiredWidth),
                    Math.max(contentHeight, requiredHeight));
        }
    }

    private void updateCanvasSize() {
        double width = Math.max(contentWidth, getWidth());
        double height = Math.max(contentHeight, getHeight());
        if (connectionCanvas.getWidth() != width) {
            connectionCanvas.setWidth(width);
        }
        if (connectionCanvas.getHeight() != height) {
            connectionCanvas.setHeight(height);
        }
        connectionCanvas.relocate(0.0, 0.0);
    }

    private void redrawConnections() {
        GraphicsContext graphics = connectionCanvas.getGraphicsContext2D();
        graphics.clearRect(0.0, 0.0, connectionCanvas.getWidth(),
                connectionCanvas.getHeight());
    }

    private record GraphConnection(VisualNode source, VisualNode target) {
    }
}
