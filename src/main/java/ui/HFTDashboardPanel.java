package ui;

import analysis.MemoryOrderLinter;
import analysis.StoreForwardingDetector;
import analysis.TrojanAllocDetector;
import analysis.ZeroAllocLinter;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.hotpath.FileAnalyzer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class HFTDashboardPanel extends JPanel {

    private final Project project;
    private final JBLabel lblHealthScore;
    private final JBLabel lblLatencyTax;
    private final DefaultTableModel tableModel;
    private final JBTable issuesTable;

    public static class IssueItem {
        public final String severity;
        public final String type;
        public final String structAndField;
        public final int penaltyCycles;
        public final double penaltyNs;
        public final int docOffset;

        public IssueItem(String severity, String type, String structAndField, int penaltyCycles, double penaltyNs, int docOffset) {
            this.severity = severity;
            this.type = type;
            this.structAndField = structAndField;
            this.penaltyCycles = penaltyCycles;
            this.penaltyNs = penaltyNs;
            this.docOffset = docOffset;
        }
    }

    private final List<IssueItem> currentIssues = new ArrayList<>();

    public HFTDashboardPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        JPanel summaryCard = new JPanel(new GridLayout(2, 1, 0, 4));
        summaryCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8)
        ));

        lblHealthScore = new JBLabel("<html><b>Memory Health Score:</b> <span style='color:green;'>100%</span></html>");
        lblLatencyTax = new JBLabel("<html><b>Est. Latency Overhead:</b> 0 cycles (0.0 ns)</html>");
        summaryCard.add(lblHealthScore);
        summaryCard.add(lblLatencyTax);

        String[] columns = {"Severity", "Penalty", "Hazard Type", "Target"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        issuesTable = new JBTable(tableModel);
        issuesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        issuesTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        issuesTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        issuesTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        issuesTable.getColumnModel().getColumn(3).setPreferredWidth(120);

        issuesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = issuesTable.getSelectedRow();
                if (row >= 0 && row < currentIssues.size()) {
                    IssueItem issue = currentIssues.get(row);
                    jumpToCode(issue.docOffset);
                }
            }
        });

        JButton btnRefresh = new JButton("Scan Active File");
        btnRefresh.setFocusable(false);
        btnRefresh.addActionListener(e -> refreshDashboard());

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(summaryCard, BorderLayout.CENTER);
        headerPanel.add(btnRefresh, BorderLayout.SOUTH);

        add(headerPanel, BorderLayout.NORTH);
        add(new JBScrollPane(issuesTable), BorderLayout.CENTER);

        refreshDashboard();
    }

    public void refreshDashboard() {
        tableModel.setRowCount(0);
        currentIssues.clear();

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            lblHealthScore.setText("<html><b>Memory Health Score:</b> N/A (no file open)</html>");
            lblLatencyTax.setText("<html><b>Est. Latency Overhead:</b> 0 ns</html>");
            return;
        }

        String text = editor.getDocument().getText();
        FileAnalyzer.Result result = FileAnalyzer.analyze(text);
        if (result == null || result.structs == null || result.structs.isEmpty()) {
            lblHealthScore.setText("<html><b>Memory Health Score:</b> <span style='color:green;'>100%</span></html>");
            lblLatencyTax.setText("<html><b>Est. Latency Overhead:</b> 0 ns (No structs)</html>");
            return;
        }

        int totalCycles = 0;
        int totalWastedBytes = 0;

        for (FileAnalyzer.MarkedStruct m : result.structs) {
            String sName = m.info.name;

            // 1. False Sharing
            for (Integer fieldIdx : m.risky.keySet()) {
                String desc = m.risky.get(fieldIdx);
                int cycles = desc.contains("Write-Write") ? 200 : 70;
                double ns = cycles / 4.5;
                int offset = m.fieldEnds != null && fieldIdx < m.fieldEnds.length ? m.fieldEnds[fieldIdx] : m.region.bStart();
                currentIssues.add(new IssueItem("CRITICAL", desc.contains("Write-Write") ? "MESI Ping-Pong" : "Read Invalidation",
                        sName + "::field#" + fieldIdx, cycles, ns, offset));
                totalCycles += cycles;
            }

            // 2. Cache Line Splits
            if (m.layout != null && m.layout.fields != null) {
                for (dev.hotpath.CppStructParser.Field f : m.layout.fields) {
                    if (f.size > 1 && (f.offset / 64 != (f.offset + f.size - 1) / 64)) {
                        int cycles = 20;
                        currentIssues.add(new IssueItem("HIGH", "Cache Line Split", sName + "::" + f.name, cycles, cycles / 4.5, m.region.bStart() + f.start));
                        totalCycles += cycles;
                    }
                }
            }

            // 3. Wasted Padding
            if (m.layout != null && m.layout.wasted > 0) {
                totalWastedBytes += m.layout.wasted;
                currentIssues.add(new IssueItem("INFO", "Padding Waste (" + m.layout.wasted + "B)", sName, 2, 0.4, m.region.bStart()));
            }

            int bStart = Math.max(0, m.region.bStart());
            int bEnd = Math.min(m.region.bEnd(), text.length());
            String blockText = text.substring(bStart, bEnd);

            // 4. Zero Alloc Linter
            List<ZeroAllocLinter.AllocationHazard> hazards = ZeroAllocLinter.inspectMethodBodies(blockText, bStart);
            for (ZeroAllocLinter.AllocationHazard h : hazards) {
                int cycles = 500;
                currentIssues.add(new IssueItem("CRITICAL", "Heap Alloc: " + h.keyword, sName, cycles, cycles / 4.5, h.offset));
                totalCycles += cycles;
            }

            // 5. STLF Stalls
            if (m.layout != null) {
                List<StoreForwardingDetector.StlfHazard> stlf = StoreForwardingDetector.detect(m.layout);
                for (StoreForwardingDetector.StlfHazard sh : stlf) {
                    int cycles = 18;
                    currentIssues.add(new IssueItem("HIGH", "Store-Forwarding Stall", sName + "::" + sh.loadField.name, cycles, cycles / 4.5, m.region.bStart() + sh.loadField.start));
                    totalCycles += cycles;
                }
            }

            // 6. Memory Order seq_cst Linter
            List<MemoryOrderLinter.MemoryOrderHazard> moHazards = MemoryOrderLinter.inspect(m.info, blockText, bStart);
            for (MemoryOrderLinter.MemoryOrderHazard moh : moHazards) {
                int cycles = 35;
                currentIssues.add(new IssueItem("HIGH", "seq_cst Penalty", sName + "::" + moh.atomicName, cycles, cycles / 4.5, moh.offsetInDoc));
                totalCycles += cycles;
            }

            // 7. Trojan Allocators (скрытые контейнеры)
            List<TrojanAllocDetector.TrojanHazard> trojans = TrojanAllocDetector.inspectFields(m.info);
            for (TrojanAllocDetector.TrojanHazard th : trojans) {
                int cycles = 250;
                currentIssues.add(new IssueItem("HIGH", "Hidden Allocator: " + th.typeName, sName + "::" + th.fieldName, cycles, cycles / 4.5, bStart));
                totalCycles += cycles;
            }
        }

        currentIssues.sort((a, b) -> Integer.compare(b.penaltyCycles, a.penaltyCycles));

        for (IssueItem item : currentIssues) {
            tableModel.addRow(new Object[]{item.severity, item.penaltyCycles + " cyc", item.type, item.structAndField});
        }

        int health = Math.max(0, 100 - (totalCycles / 15) - (totalWastedBytes / 4));
        String healthColor = health > 80 ? "#27ae60" : health > 50 ? "#e67e22" : "#c0392b";
        lblHealthScore.setText("<html><b>Memory Health Score:</b> <span style='color:" + healthColor + "; font-size:13px;'>" + health + "/100</span></html>");
        lblLatencyTax.setText(String.format("<html><b>Est. Latency Overhead:</b> ~%d cycles (%.1f ns)</html>", totalCycles, totalCycles / 4.5));
    }

    private void jumpToCode(int offset) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null && offset >= 0 && offset < editor.getDocument().getTextLength()) {
            editor.getCaretModel().moveToOffset(offset);
            editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER);
        }
    }
}