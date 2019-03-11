package me.shib.bugaudit;

import me.shib.bugaudit.scanner.BugAuditScanner;
import me.shib.bugaudit.scanner.GitRepo;

import java.util.ArrayList;
import java.util.List;

public class BugAudit {

    public static synchronized List<Exception> audit() throws Exception {
        List<Exception> exceptions = new ArrayList<>();
        List<BugAuditScanner> scanners = BugAuditScanner.getScanners(GitRepo.getRepo());
        for (BugAuditScanner scanner : scanners) {
            System.out.println("Now running scanner: " + scanner.getTool());
            scanner.scan();
            try {
                BugAuditWorker bugAuditWorker = new BugAuditWorker(scanner.getBugAuditScanResult());
                bugAuditWorker.processResult();
                exceptions.addAll(bugAuditWorker.getExceptions());
            } catch (Exception e) {
                e.printStackTrace();
                exceptions.add(e);
            }
        }
        return exceptions;
    }

}
