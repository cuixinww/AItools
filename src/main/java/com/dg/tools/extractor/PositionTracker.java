package com.dg.tools.extractor;

import java.util.ArrayDeque;
import java.util.Deque;

public class PositionTracker {
    private int currentLevel;
    private final Deque<Integer> positionStack = new ArrayDeque<>();

    public PositionTracker() {
        this.currentLevel = 0;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public void enterEmbedding(int parentPosition) {
        positionStack.push(parentPosition);
        currentLevel++;
    }

    public void exitEmbedding() {
        if (!positionStack.isEmpty()) {
            positionStack.pop();
        }
        if (currentLevel > 0) {
            currentLevel--;
        }
    }

    public void reset() {
        currentLevel = 0;
        positionStack.clear();
    }
}
