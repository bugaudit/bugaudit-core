package me.shib.bugaudit;

import me.shib.bugaudit.scanner.BugAuditScanner;
import me.shib.bugaudit.scanner.GitRepo;

import java.util.ArrayList;
import java.util.List;

public final class BugAudit {

    public static synchronized List<Exception> audit() {
        List<Exception> exceptions = new ArrayList<>();
        List<BugAuditScanner> scanners = BugAuditScanner.getScanners(GitRepo.getRepo());
        List<BugAuditWorker.ProcessedCount> processedCounts = new ArrayList<>();
        for (BugAuditScanner scanner : scanners) {
            try {
                System.out.println("Now running scanner: " + scanner.getTool());
                scanner.scan();
                BugAuditWorker bugAuditWorker = new BugAuditWorker(scanner.getBugAuditScanResult());
                bugAuditWorker.processResult();
                processedCounts.add(bugAuditWorker.getProcessedCount());
                exceptions.addAll(bugAuditWorker.getExceptions());
            } catch (Exception e) {
                e.printStackTrace();
                exceptions.add(e);
            }
        }
        return exceptions;
    }

    private static void printChangelog(List<BugAuditWorker.ProcessedCount> processedCounts) {
        int created = 0;
        int updated = 0;
        int commented = 0;
        for (BugAuditWorker.ProcessedCount processedCount : processedCounts) {
            created += processedCount.getCreated();
            updated += processedCount.getUpdated();
            commented += processedCount.getCommented();
        }
        StringBuilder changelog = new StringBuilder();
        changelog.append("\n[BUILD CHANGELOG]");
        if (created == 0 && updated == 0 && commented == 0) {
            changelog.append(" No change(s)");
        } else {
            changelog.append(" Created(").append(created).append(")")
                    .append(" Updated(").append(updated).append(")")
                    .append(" Commented(").append(commented).append(")");
        }
        System.out.println(changelog);
    }

}
