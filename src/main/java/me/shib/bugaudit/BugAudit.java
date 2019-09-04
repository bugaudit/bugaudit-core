package me.shib.bugaudit;

import me.shib.bugaudit.commons.BugAuditException;
import me.shib.bugaudit.scanner.BugAuditScanner;
import me.shib.bugaudit.scanner.GitRepo;
import me.shib.bugaudit.scanner.Lang;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BugAudit {

    public static synchronized List<Exception> audit() throws BugAuditException, IOException, InterruptedException {
        List<Exception> exceptions = new ArrayList<>();
        Lang lang = GitRepo.getRepo().getLang();
        if (lang == null) {
            lang = Lang.Unknown;
        }
        List<BugAuditScanner> scanners = BugAuditScanner.getScanners(lang);
        if (scanners.size() == 0) {
            System.out.println("No scanners available for " + lang);
            System.exit(1);
        }
        List<BugAuditWorker.ProcessedCount> processedCounts = new ArrayList<>();
        BugAuditScanner.buildProject();
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
        printChangelog(processedCounts);
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
        String changelog = "\n[BUILD CHANGELOG]" +
                " Created(" + created + ")" +
                " Updated(" + updated + ")" +
                " Commented(" + commented + ")";
        System.out.println(changelog);
    }

}
