package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * A monthly report that has inner record types Summary and Charts.
 * These inner types clash with WeeklyReport.Summary and WeeklyReport.Charts.
 */
public record MonthlyReport(
    String reportId,
    String month,
    Summary summary,
    Charts charts
) {

    public record Summary(
        double totalRevenue,
        int totalOrders,
        double averageOrderValue
    ) {}

    public record Charts(
        List<String> revenueByWeek,
        List<String> ordersByCategory
    ) {}
}
