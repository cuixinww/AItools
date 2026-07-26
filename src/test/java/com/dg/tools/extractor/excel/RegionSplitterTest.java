package com.dg.tools.extractor.excel;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RegionSplitterTest {

    @Test
    void splitEmptyRows() {
        assertThat(RegionSplitter.split(List.of())).isEmpty();
    }

    @Test
    void splitAllEmptyCols() {
        List<List<String>> rows = List.of(
                List.of("", ""),
                List.of("", "")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).isEmpty();
    }

    @Test
    void splitSingleRegion() {
        List<List<String>> rows = List.of(
                List.of("a", "b"),
                List.of("c", "d")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).startCol).isEqualTo(0);
        assertThat(regions.get(0).rows).containsExactly(
                List.of("a", "b"),
                List.of("c", "d")
        );
    }

    @Test
    void splitTwoRegionsWithEmptyGap() {
        List<List<String>> rows = List.of(
                List.of("a", "", "", "", "e"),
                List.of("b", "", "", "", "f")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).rows).containsExactly(
                List.of("a"),
                List.of("b")
        );
        assertThat(regions.get(1).rows).containsExactly(
                List.of("e"),
                List.of("f")
        );
    }

    @Test
    void splitSingleEmptyColumnNotSplit() {
        List<List<String>> rows = List.of(
                List.of("a", "", "b"),
                List.of("c", "", "d")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).startCol).isEqualTo(0);
        assertThat(regions.get(0).endCol).isEqualTo(2);
    }

    @Test
    void splitRowsWithNullValues() {
        List<List<String>> rows = List.of(
                Arrays.asList("a", null, "b")
        );
        assertThatCode(() -> RegionSplitter.split(rows)).doesNotThrowAnyException();
        assertThat(RegionSplitter.split(rows)).hasSize(1);
    }

    @Test
    void splitLeadingAndTrailingEmptyCols() {
        List<List<String>> rows = List.of(
                List.of("", "", "x", "", ""),
                List.of("", "", "y", "", "")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).startCol).isEqualTo(2);
        assertThat(regions.get(0).endCol).isEqualTo(2);
    }

    @Test
    void splitThreeRegions() {
        List<List<String>> rows = List.of(
                List.of("a", "", "", "b", "", "", "c"),
                List.of("d", "", "", "e", "", "", "f")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(3);
        assertThat(regions.get(0).rows).containsExactly(List.of("a"), List.of("d"));
        assertThat(regions.get(1).rows).containsExactly(List.of("b"), List.of("e"));
        assertThat(regions.get(2).rows).containsExactly(List.of("c"), List.of("f"));
    }

    @Test
    void splitRowSizePadding() {
        List<List<String>> rows = List.of(
                List.of("a", "b"),
                List.of("c")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).rows.get(1)).containsExactly("c", "");
    }

    @Test
    void splitAllRowsSameLengthNormalization() {
        List<List<String>> rows = List.of(
                List.of("x", "y"),
                List.of("z")
        );
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);
        assertThat(regions.get(0).rows.get(0)).hasSize(2);
        assertThat(regions.get(0).rows.get(1)).hasSize(2);
    }
}
