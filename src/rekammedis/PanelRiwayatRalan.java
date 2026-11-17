package rekammedis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Dimension;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

/** Read-only outpatient history with a shared visit selector for all history tabs. */
public final class PanelRiwayatRalan extends JPanel {
    private static final int PAGE_SIZE = 10;

    private final Connection connection;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicLong requestSequence = new AtomicLong();
    private final PanelRiwayatSoap history;
    private final JLabel patientInfo = new JLabel("No. R.M.: -", SwingConstants.LEFT);
    private final JLabel selectedInfo = new JLabel("Pilih No. Rawat untuk melihat riwayat.", SwingConstants.LEFT);
    private final JLabel status = new JLabel("", SwingConstants.LEFT);
    private final JTextField search = new JTextField();
    private final JButton loadMore = new JButton("Muat kunjungan lebih lama");
    private final DefaultTableModel tableModel;
    private final JTable visitsTable;
    private final Timer searchTimer;
    private final List<Visit> visits = new ArrayList<>();
    private volatile String activeNoRawat = "";
    private String selectedNoRawat = "";
    private String searchTerm = "";
    private int nextOffset;
    private boolean hasMore;
    private boolean loading;
    private boolean updatingTable;

    public PanelRiwayatRalan(Connection connection) {
        this.connection = connection;
        this.history = new PanelRiwayatSoap(connection, loader, false, false, true);
        setLayout(new BorderLayout(0, 5));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(new Color(210, 225, 217)));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("RIWAYAT PASIEN");
        title.setFont(new Font("Tahoma", Font.BOLD, 12));
        title.setForeground(new Color(30, 80, 70));
        JLabel readOnly = new JLabel("HANYA BACA", SwingConstants.CENTER);
        readOnly.setFont(new Font("Tahoma", Font.BOLD, 9));
        readOnly.setForeground(new Color(34, 112, 76));
        readOnly.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(191, 222, 204)),
                BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        header.add(title, BorderLayout.WEST);
        header.add(readOnly, BorderLayout.EAST);

        patientInfo.setFont(new Font("Tahoma", Font.PLAIN, 10));
        patientInfo.setBorder(BorderFactory.createEmptyBorder(3, 2, 5, 2));
        selectedInfo.setFont(new Font("Tahoma", Font.BOLD, 10));
        selectedInfo.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        status.setFont(new Font("Tahoma", Font.PLAIN, 9));
        status.setForeground(new Color(105, 122, 112));

        tableModel = new DefaultTableModel(new Object[]{"Tanggal", "No. Rawat", "Poli", "Data"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        visitsTable = new JTable(tableModel);
        visitsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        visitsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        visitsTable.setFont(new Font("Tahoma", Font.PLAIN, 10));
        visitsTable.setRowHeight(22);
        visitsTable.setDefaultRenderer(Object.class, new fungsi.WarnaTable());
        TableColumn dateColumn = visitsTable.getColumnModel().getColumn(0);
        dateColumn.setPreferredWidth(78);
        TableColumn noRawatColumn = visitsTable.getColumnModel().getColumn(1);
        noRawatColumn.setPreferredWidth(140);
        TableColumn poliColumn = visitsTable.getColumnModel().getColumn(2);
        poliColumn.setPreferredWidth(105);
        visitsTable.removeColumn(visitsTable.getColumnModel().getColumn(3));
        visitsTable.getSelectionModel().addListSelectionListener((ListSelectionEvent event) -> {
            if (event.getValueIsAdjusting() || updatingTable) {
                return;
            }
            int selectedRow = visitsTable.getSelectedRow();
            if (selectedRow >= 0) {
                int modelRow = visitsTable.convertRowIndexToModel(selectedRow);
                Visit visit = (Visit) tableModel.getValueAt(modelRow, 3);
                selectVisit(visit);
            }
        });

        search.setFont(new Font("Tahoma", Font.PLAIN, 10));
        search.setToolTipText("Cari tanggal, poli, dokter, atau No. Rawat");
        search.setColumns(14);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
        });
        searchTimer = new Timer(350, event -> {
            searchTerm = search.getText().trim();
            loadPage(0, true);
        });
        searchTimer.setRepeats(false);

        loadMore.setFont(new Font("Tahoma", Font.PLAIN, 10));
        loadMore.setFocusPainted(false);
        loadMore.addActionListener(event -> {
            if (!loading && hasMore) {
                loadPage(nextOffset, false);
            }
        });

        JPanel selector = new JPanel(new BorderLayout(0, 3));
        selector.setOpaque(false);
        selector.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 225, 217)),
                BorderFactory.createEmptyBorder(4, 5, 5, 5)));
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        JLabel choose = new JLabel("Pilih kunjungan:");
        choose.setFont(new Font("Tahoma", Font.BOLD, 10));
        searchRow.add(choose, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        selector.add(searchRow, BorderLayout.NORTH);
        JScrollPane visitScroll = new JScrollPane(visitsTable);
        visitScroll.setBorder(BorderFactory.createLineBorder(new Color(224, 234, 228)));
        visitScroll.setPreferredSize(new Dimension(360, 122));
        selector.add(visitScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 0));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(loadMore, BorderLayout.EAST);
        selector.add(bottom, BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(5, 6, 0, 6));
        top.add(header, BorderLayout.NORTH);
        JPanel context = new JPanel(new BorderLayout());
        context.setOpaque(false);
        context.add(patientInfo, BorderLayout.NORTH);
        context.add(selectedInfo, BorderLayout.SOUTH);
        top.add(context, BorderLayout.CENTER);
        top.add(selector, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(history, BorderLayout.CENTER);
    }

    private void scheduleSearch() {
        searchTimer.restart();
    }

    /** Sets the active visit; its exact no_rawat is selected by default. */
    public void setNoRawat(String noRawat) {
        String normalized = noRawat == null ? "" : noRawat.trim();
        if (normalized.equals(activeNoRawat)) {
            return;
        }
        activeNoRawat = normalized;
        selectedNoRawat = normalized;
        searchTimer.stop();
        search.setText("");
        searchTerm = "";
        nextOffset = 0;
        hasMore = false;
        loading = false;
        visits.clear();
        renderVisits();
        if (normalized.isEmpty()) {
            patientInfo.setText("No. R.M.: -");
            selectedInfo.setText("Pilih No. Rawat untuk melihat riwayat.");
            status.setText("");
            loadMore.setEnabled(false);
            history.setNoRawat("");
            return;
        }
        selectedInfo.setText("No. Rawat dipilih: " + normalized + " · Kunjungan aktif");
        patientInfo.setText("Memuat identitas pasien...");
        status.setText("Memuat kunjungan...");
        loadMore.setEnabled(false);
        history.setNoRawat(normalized);
        loadPage(0, true);
    }

    private void loadPage(int offset, boolean reset) {
        if (activeNoRawat.isEmpty() || loader.isShutdown()) {
            return;
        }
        final String requestNoRawat = activeNoRawat;
        final String requestSearch = searchTerm;
        final long request = requestSequence.incrementAndGet();
        loading = true;
        if (reset) {
            visits.clear();
            nextOffset = 0;
            hasMore = false;
            renderVisits();
        }
        status.setText(requestSearch.isEmpty() ? "Memuat kunjungan..." : "Mencari kunjungan...");
        loadMore.setEnabled(false);
        try {
            loader.submit(() -> {
                List<Visit> page = new ArrayList<>();
                String error = null;
                String sql = "select rp.no_rawat,rp.no_rkm_medis,p.nm_pasien,rp.tgl_registrasi,rp.jam_reg,"
                        + "coalesce(po.nm_poli,'') as poli,coalesce(d.nm_dokter,'') as dokter "
                        + "from reg_periksa rp inner join pasien p on rp.no_rkm_medis=p.no_rkm_medis "
                        + "left join poliklinik po on rp.kd_poli=po.kd_poli "
                        + "left join dokter d on rp.kd_dokter=d.kd_dokter "
                        + "where rp.no_rkm_medis=(select r.no_rkm_medis from reg_periksa r where r.no_rawat=?) "
                        + "and (?='' or rp.no_rawat like ? or date_format(rp.tgl_registrasi,'%d-%m-%Y') like ? "
                        + "or po.nm_poli like ? or d.nm_dokter like ?) "
                        + "order by (rp.no_rawat=?) desc,rp.tgl_registrasi desc,rp.jam_reg desc "
                        + "limit ? offset ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    String like = "%" + requestSearch + "%";
                    statement.setString(1, requestNoRawat);
                    statement.setString(2, requestSearch);
                    statement.setString(3, like);
                    statement.setString(4, like);
                    statement.setString(5, like);
                    statement.setString(6, like);
                    statement.setString(7, requestNoRawat);
                    statement.setInt(8, PAGE_SIZE);
                    statement.setInt(9, offset);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            page.add(new Visit(result.getString("no_rawat"), result.getString("no_rkm_medis"),
                                    result.getString("nm_pasien"), formatDate(result.getString("tgl_registrasi")),
                                    result.getString("jam_reg"), result.getString("poli"),
                                    result.getString("dokter")));
                        }
                    }
                } catch (Exception exception) {
                    error = "Daftar kunjungan tidak dapat dimuat. Periksa koneksi database.";
                    System.out.println("Notifikasi riwayat rawat jalan: " + exception.getClass().getSimpleName());
                }
                final List<Visit> loaded = page;
                final String loadError = error;
                SwingUtilities.invokeLater(() -> {
                    if (requestSequence.get() != request || !activeNoRawat.equals(requestNoRawat)
                            || loader.isShutdown()) {
                        return;
                    }
                    loading = false;
                    if (loadError != null) {
                        status.setText(loadError);
                        loadMore.setEnabled(false);
                        return;
                    }
                    if (!loaded.isEmpty() && offset == 0) {
                        patientInfo.setText("No. R.M.: " + loaded.get(0).noRm + " · " + loaded.get(0).patientName);
                    }
                    visits.addAll(loaded);
                    nextOffset = offset + loaded.size();
                    hasMore = loaded.size() == PAGE_SIZE;
                    if (offset == 0 && !requestSearch.isEmpty()) {
                        status.setText(loaded.size() + " hasil untuk pencarian ini.");
                    } else if (requestSearch.isEmpty()) {
                        status.setText("Menampilkan " + visits.size() + " kunjungan terbaru.");
                    } else {
                        status.setText("Menampilkan " + visits.size() + " hasil pencarian.");
                    }
                    loadMore.setText(requestSearch.isEmpty()
                            ? "Muat kunjungan lebih lama" : "Muat hasil berikutnya");
                    loadMore.setEnabled(hasMore);
                    if (offset == 0 && selectedNoRawat.isEmpty() && !loaded.isEmpty()) {
                        selectedNoRawat = loaded.get(0).noRawat;
                        history.setNoRawat(selectedNoRawat);
                    } else if (offset == 0 && requestSearch.isEmpty() && !loaded.isEmpty()
                            && !containsNoRawat(loaded, selectedNoRawat)
                            && !containsNoRawat(visits, selectedNoRawat)) {
                        selectedNoRawat = loaded.get(0).noRawat;
                        history.setNoRawat(selectedNoRawat);
                        selectedInfo.setText("No. Rawat dipilih: " + selectedNoRawat + " · Kunjungan terbaru");
                    }
                    renderVisits();
                });
            });
        } catch (RejectedExecutionException ignored) {
            loading = false;
        }
    }

    private String formatDate(String sqlDate) {
        if (sqlDate == null || sqlDate.trim().isEmpty()) {
            return "";
        }
        try {
            return new SimpleDateFormat("dd-MM-yyyy").format(Date.valueOf(sqlDate));
        } catch (IllegalArgumentException exception) {
            return sqlDate;
        }
    }

    private boolean containsNoRawat(List<Visit> candidates, String noRawat) {
        for (Visit visit : candidates) {
            if (visit.noRawat.equals(noRawat)) {
                return true;
            }
        }
        return false;
    }

    private void renderVisits() {
        updatingTable = true;
        tableModel.setRowCount(0);
        int selectedRow = -1;
        for (Visit visit : visits) {
            int row = tableModel.getRowCount();
            tableModel.addRow(new Object[]{visit.date, visit.noRawat, visit.poli, visit});
            if (visit.noRawat.equals(selectedNoRawat)) {
                selectedRow = row;
            }
        }
        visitsTable.clearSelection();
        if (selectedRow >= 0) {
            int viewRow = visitsTable.convertRowIndexToView(selectedRow);
            visitsTable.getSelectionModel().setSelectionInterval(viewRow, viewRow);
        }
        updatingTable = false;
    }

    private void selectVisit(Visit visit) {
        if (visit.noRawat.equals(selectedNoRawat)) {
            return;
        }
        selectedNoRawat = visit.noRawat;
        selectedInfo.setText("No. Rawat dipilih: " + visit.noRawat + " · " + visit.date);
        history.setNoRawat(visit.noRawat);
        renderVisits();
    }

    public void dispose() {
        requestSequence.incrementAndGet();
        searchTimer.stop();
        loader.shutdownNow();
        history.dispose();
    }

    private static final class Visit {
        private final String noRawat;
        private final String noRm;
        private final String patientName;
        private final String date;
        private final String time;
        private final String poli;
        private final String doctor;

        private Visit(String noRawat, String noRm, String patientName, String date,
                String time, String poli, String doctor) {
            this.noRawat = value(noRawat);
            this.noRm = value(noRm);
            this.patientName = value(patientName);
            this.date = value(date);
            this.time = value(time);
            this.poli = value(poli);
            this.doctor = value(doctor);
        }

        private static String value(String text) {
            return text == null ? "" : text;
        }
    }
}
