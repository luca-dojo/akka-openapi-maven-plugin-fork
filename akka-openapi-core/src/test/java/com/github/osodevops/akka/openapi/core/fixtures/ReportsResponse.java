package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * Response wrapper containing both monthly and weekly reports.
 * This triggers generation of the full type hierarchy including the
 * clashing inner record types (Summary, Charts).
 */
public record ReportsResponse(
    List<MonthlyReport> monthlyReports,
    List<WeeklyReport> weeklyReports
) {}
