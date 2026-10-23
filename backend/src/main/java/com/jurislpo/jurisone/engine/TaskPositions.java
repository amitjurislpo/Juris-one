package com.jurislpo.jurisone.engine;

/**
 * Card order inside a board column (plan §8.2): positions are spaced {@link #GAP} apart, a card dropped between two others takes
 * the middle, and a column without room is renumbered first.
 */
public final class TaskPositions {
    private TaskPositions() {}

    public static final long GAP = 65_536;

    /** The bottom of a column whose lowest card is at {@code maxPosition} (null = empty column). */
    public static long append(Long maxPosition) {
        return maxPosition == null ? GAP : maxPosition + GAP;
    }

    /**
     * The position between two neighbours (either may be null: top or bottom of the column); null when there is no room and the
     * column must be renumbered first.
     */
    public static Long between(Long previousPosition, Long nextPosition) {
        if (previousPosition == null && nextPosition == null) return GAP;
        if (previousPosition == null) return nextPosition - GAP;
        if (nextPosition == null) return previousPosition + GAP;
        if (nextPosition - previousPosition < 2) return null;
        return previousPosition + (nextPosition - previousPosition) / 2;
    }

    /** The position of the card at {@code index} (0-based) after renumbering a column. */
    public static long renumbered(int index) {
        return (index + 1L) * GAP;
    }
}
