package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * A weekly report that has inner record types Summary and Charts.
 * These inner types clash with MonthlyReport.Summary and MonthlyReport.Charts.
 */
public record WeeklyReport(
    String reportId,
    String weekNumber,
    Summary summary,
    Charts charts
) {

    public record Summary(
        double weeklyRevenue,
        int weeklyOrders
    ) {}

    public record Charts(
        List<String> revenueByDay,
        List<String> ordersByDay
    ) {}
}
