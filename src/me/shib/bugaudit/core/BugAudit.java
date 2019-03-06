package me.shib.bugaudit.core;

import me.shib.bugaudit.commons.BugAuditResult;
import me.shib.bugaudit.probe.ProbeScanner;
import me.shib.bugaudit.tracker.BatWorker;

import java.util.ArrayList;
import java.util.List;

public class BugAudit {

    public static List<Exception> audit() {
        List<Exception> exceptions = new ArrayList<>();
        List<BugAuditResult> results = ProbeScanner.getAuditResultsFromScanners();
        for (BugAuditResult result : results) {
            try {
                BatWorker batWorker = new BatWorker(result);
                batWorker.processResult();
            } catch (Exception e) {
                e.printStackTrace();
                exceptions.add(e);
            }
        }
        return exceptions;
    }

}
