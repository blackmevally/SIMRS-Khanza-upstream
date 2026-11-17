package rekammedis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Insets;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.border.EmptyBorder;
import fungsi.koneksiDB;

/** Read-only encounter history panel, filtered by the active no_rawat. */
public final class PanelRiwayatSoap extends JPanel {
    public interface OperationEditHandler {
        void editOperationReport(String noRawat, String tglOperasi);
    }

    private final Connection connection;
    private final JLabel labelNoRawat = new JLabel("Pilih No. Rawat", SwingConstants.LEFT);
    private final JTabbedPane tabs = new JTabbedPane();
    private final List<JEditorPane> contents = new ArrayList<>();
    private final PanelRiwayatTtv ttvHistory;
    private final ExecutorService loader;
    private final boolean ownsLoader;
    private final boolean rawatJalan;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Set<String> expandedIssued = new HashSet<>();
    private volatile String activeNoRawat = "";
    private OperationEditHandler operationEditHandler;
    private List<Entry> lastLoadedEntries = new ArrayList<>();
    private int lastLoadedTab = -1;

    private static final String[] TAB_NAMES = {
        "SOAPIE", "OBAT & BHP", "LABORATORIUM", "RADIOLOGI", "OPERASI"
    };
    private static final int[] TAB_CATEGORIES_RANAP = {1, 2, 3, 4, 5};
    private static final int[] TAB_CATEGORIES_RALAN = {0, 2, 3, 4};

    public PanelRiwayatSoap(Connection connection) {
        this(connection, Executors.newSingleThreadExecutor(), true, true, false, true);
    }

    PanelRiwayatSoap(Connection connection, ExecutorService sharedLoader,
            boolean showHeader, boolean includeOperationTab, boolean rawatJalan) {
        this(connection, sharedLoader, showHeader, includeOperationTab, rawatJalan, false);
    }

    private PanelRiwayatSoap(Connection connection, ExecutorService sharedLoader,
            boolean showHeader, boolean includeOperationTab, boolean rawatJalan, boolean ownsLoader) {
        this.connection = connection;
        this.loader = sharedLoader;
        this.ownsLoader = ownsLoader;
        this.rawatJalan = rawatJalan;
        this.ttvHistory = new PanelRiwayatTtv(connection, loader, rawatJalan);
        setLayout(new BorderLayout(0, 5));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 225, 217)),
                new EmptyBorder(6, 7, 6, 7)));

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
                new EmptyBorder(3, 5, 3, 5)));
        header.add(title, BorderLayout.WEST);
        header.add(readOnly, BorderLayout.EAST);

        labelNoRawat.setFont(new Font("Tahoma", Font.BOLD, 10));
        labelNoRawat.setBorder(new EmptyBorder(2, 1, 2, 1));
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(labelNoRawat, BorderLayout.CENTER);
        if (showHeader) {
            add(top, BorderLayout.NORTH);
        }

        for (String tabName : TAB_NAMES) {
            if (!includeOperationTab && "OPERASI".equals(tabName)) {
                continue;
            }
            JEditorPane content = new JEditorPane();
            content.setContentType("text/html");
            content.setEditable(false);
            content.setMargin(new Insets(8, 9, 8, 9));
            content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            content.setFont(new Font("Tahoma", Font.PLAIN, 11));
            content.setText(message("Pilih No. Rawat untuk melihat riwayat."));
            content.addHyperlinkListener(event -> {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                        && event.getDescription() != null
                        && event.getDescription().startsWith("issued:")) {
                    String noResep = event.getDescription().substring("issued:".length());
                    if (!expandedIssued.remove(noResep)) {
                        expandedIssued.add(noResep);
                    }
                    if (lastLoadedTab == 1) {
                        content.setText(render(lastLoadedEntries, lastLoadedTab));
                    }
                } else if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                        && event.getDescription() != null
                        && event.getDescription().startsWith("editop:")
                        && operationEditHandler != null) {
                    String tglOperasi = event.getDescription().substring("editop:".length()).replace('T', ' ');
                    operationEditHandler.editOperationReport(activeNoRawat, tglOperasi);
                } else if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                        && event.getURL() != null
                        && ("http".equalsIgnoreCase(event.getURL().getProtocol())
                            || "https".equalsIgnoreCase(event.getURL().getProtocol()))) {
                    try {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(event.getURL().toURI());
                        }
                    } catch (Exception exception) {
                        JOptionPane.showMessageDialog(this,
                                "Gambar radiologi tidak dapat dibuka di browser: " + exception.getMessage());
                    }
                }
            });
            JScrollPane scroll = new JScrollPane(content);
            scroll.setBorder(BorderFactory.createLineBorder(new Color(226, 234, 229)));
            tabs.addTab(tabName, scroll);
            contents.add(content);
        }
        tabs.addTab("PEMERIKSAAN TTV", ttvHistory);
        tabs.setFont(new Font("Tahoma", Font.PLAIN, 10));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener((ChangeListener) event -> refreshActiveTab());
        add(tabs, BorderLayout.CENTER);
    }

    public void setNoRawat(String noRawat) {
        activeNoRawat = noRawat == null ? "" : noRawat.trim();
        expandedIssued.clear();
        labelNoRawat.setText(activeNoRawat.isEmpty()
                ? "No. Rawat belum dipilih" : "No. Rawat: " + activeNoRawat);
        requestSequence.incrementAndGet();
        if (activeNoRawat.isEmpty()) {
            for (JEditorPane content : contents) {
                content.setText(message("Pilih No. Rawat untuk melihat riwayat."));
            }
            ttvHistory.setNoRawat("");
            return;
        }
        refreshActiveTab();
    }

    public void setOperationEditHandler(OperationEditHandler handler) {
        operationEditHandler = handler;
    }

    private void refreshActiveTab() {
        if (activeNoRawat.isEmpty() || loader.isShutdown()) {
            return;
        }
        final String noRawat = activeNoRawat;
        final int tabIndex = tabs.getSelectedIndex();
        if (tabIndex < 0) {
            return;
        }
        if (tabIndex == contents.size()) {
            ttvHistory.setNoRawat(noRawat);
            return;
        }
        final long request = requestSequence.incrementAndGet();
        contents.get(tabIndex).setText(message("Memuat " + TAB_NAMES[tabIndex].toLowerCase() + "…"));
        try {
            loader.submit(() -> loadTab(noRawat, tabIndex, request));
        } catch (RejectedExecutionException ignored) {
            // The parent dialog is closing.
        }
    }

    private void loadTab(String noRawat, int tabIndex, long request) {
        List<Entry> entries = new ArrayList<>();
        String error = null;
        try {
            int[] categories = rawatJalan ? TAB_CATEGORIES_RALAN : TAB_CATEGORIES_RANAP;
            entries = query(noRawat, categories[tabIndex]);
        } catch (Exception exception) {
            error = "Riwayat tidak dapat dimuat. Periksa koneksi database dan struktur tabel.";
            System.out.println("Notifikasi riwayat pasien: " + exception.getClass().getSimpleName());
        }
        final List<Entry> loadedEntries = entries;
        final String html = error == null ? render(loadedEntries, tabIndex) : message(error);
        SwingUtilities.invokeLater(() -> {
            if (requestSequence.get() == request && tabs.getSelectedIndex() == tabIndex && !loader.isShutdown()) {
                lastLoadedEntries = loadedEntries;
                lastLoadedTab = tabIndex;
                contents.get(tabIndex).setText(html);
                contents.get(tabIndex).setCaretPosition(0);
            }
        });
    }

    private List<Entry> query(String noRawat, int category) throws Exception {
        if (category == 2) {
            return queryResep(noRawat);
        }
        if (category == 3) {
            return queryLaboratorium(noRawat);
        }
        if (category == 4) {
            return queryRadiologi(noRawat);
        }
        String sql;
        switch (category) {
            case 0:
                if (rawatJalan) {
                    sql = "select p.tgl_perawatan as tanggal,p.jam_rawat as jam,coalesce(pg.nama,'') as judul,"
                        + "concat('S: ',coalesce(p.keluhan,''),'\\nO: ',coalesce(p.pemeriksaan,''),"
                        + "'\\nA: ',coalesce(p.penilaian,''),'\\nP: ',coalesce(p.rtl,''),"
                        + "'\\nAlergi: ',coalesce(p.alergi,''),'\\nInstruksi: ',coalesce(p.instruksi,''),"
                        + "'\\nEvaluasi: ',coalesce(p.evaluasi,'')) as isi from pemeriksaan_ralan p "
                        + "left join pegawai pg on p.nip=pg.nik where p.no_rawat=? "
                        + "order by p.tgl_perawatan desc,p.jam_rawat desc";
                } else {
                    sql = "select p.tgl_perawatan as tanggal,p.jam_rawat as jam,'Pemeriksaan' as judul,"
                        + "concat('Suhu: ',coalesce(p.suhu_tubuh,'-'),' °C | Tensi: ',coalesce(p.tensi,'-'),"
                        + "' | Nadi: ',coalesce(p.nadi,'-'),' | RR: ',coalesce(p.respirasi,'-'),"
                        + "' | SpO2: ',coalesce(p.spo2,'-'),'% | GCS: ',coalesce(p.gcs,'-'),"
                        + "' | Kesadaran: ',coalesce(p.kesadaran,'-'),'\\nKeluhan: ',coalesce(p.keluhan,''),"
                        + "'\\nPemeriksaan: ',coalesce(p.pemeriksaan,'')) as isi from pemeriksaan_ranap p "
                        + "where p.no_rawat=? order by p.tgl_perawatan desc,p.jam_rawat desc";
                }
                break;
            case 1:
                sql = "select p.tgl_perawatan as tanggal,p.jam_rawat as jam,coalesce(pg.nama,'') as judul,"
                    + "concat('S: ',coalesce(p.keluhan,''),'\\nO: ',coalesce(p.pemeriksaan,''),"
                    + "'\\nA: ',coalesce(p.penilaian,''),'\\nP: ',coalesce(p.rtl,''),"
                    + "'\\nAlergi: ',coalesce(p.alergi,''),'\\nInstruksi: ',coalesce(p.instruksi,''),"
                    + "'\\nEvaluasi: ',coalesce(p.evaluasi,'')) as isi from pemeriksaan_ranap p "
                    + "left join pegawai pg on p.nip=pg.nik where p.no_rawat=? "
                    + "order by p.tgl_perawatan desc,p.jam_rawat desc";
                break;
            case 4:
                sql = "select r.tgl_periksa as tanggal,r.jam as jam,'Hasil Radiologi' as judul,r.hasil as isi "
                    + "from hasil_radiologi r where r.no_rawat=? order by r.tgl_periksa desc,r.jam desc";
                break;
            case 5:
                sql = "select date(o.tgl_operasi) as tanggal,time(o.tgl_operasi) as jam,"
                    + "p.nm_perawatan as judul,concat('Jenis anestesi: ',coalesce(o.jenis_anasthesi,'-'),"
                    + "' | Kategori: ',coalesce(o.kategori,'-'),case when lo.tanggal is null then '' else "
                    + "concat('\nDiagnosa Pre-operatif: ',coalesce(lo.diagnosa_preop,'-'),"
                    + "'\nDiagnosa Post-operatif: ',coalesce(lo.diagnosa_postop,'-'),"
                    + "'\nJaringan di-Eksisi / -Insisi: ',coalesce(lo.jaringan_dieksekusi,'-'),"
                    + "'\nSelesai Operasi: ',coalesce(lo.selesaioperasi,'-'),"
                    + "'\nDikirim Pemeriksaan PA: ',coalesce(lo.permintaan_pa,'-'),"
                    + "'\nNomor Implan: ',coalesce(lo.nomor_implan,'-'),"
                    + "'\nLaporan Operasi:\n',coalesce(lo.laporan_operasi,'')) end) as isi from operasi o "
                    + "inner join paket_operasi p on o.kode_paket=p.kode_paket "
                    + "left join laporan_operasi lo on lo.no_rawat=o.no_rawat and lo.tanggal=o.tgl_operasi "
                    + "where o.no_rawat=? order by o.tgl_operasi desc";
                break;
            case 6:
                sql = "select x.tanggal,x.jam,x.judul,x.isi from ("
                    + "select d.tgl_perawatan as tanggal,d.jam_rawat as jam,j.nm_perawatan as judul,"
                    + "concat('Dokter: ',coalesce(dr.nm_dokter,'')) as isi from rawat_inap_dr d "
                    + "inner join jns_perawatan_inap j on d.kd_jenis_prw=j.kd_jenis_prw "
                    + "inner join dokter dr on d.kd_dokter=dr.kd_dokter where d.no_rawat=? union all "
                    + "select d.tgl_perawatan,d.jam_rawat,j.nm_perawatan,concat('Petugas: ',coalesce(pt.nama,'')) "
                    + "from rawat_inap_pr d inner join jns_perawatan_inap j on d.kd_jenis_prw=j.kd_jenis_prw "
                    + "inner join petugas pt on d.nip=pt.nip where d.no_rawat=? union all "
                    + "select d.tgl_perawatan,d.jam_rawat,j.nm_perawatan,concat('Dokter: ',coalesce(dr.nm_dokter,''),"
                    + "' | Petugas: ',coalesce(pt.nama,'')) from rawat_inap_drpr d "
                    + "inner join jns_perawatan_inap j on d.kd_jenis_prw=j.kd_jenis_prw "
                    + "inner join dokter dr on d.kd_dokter=dr.kd_dokter inner join petugas pt on d.nip=pt.nip "
                    + "where d.no_rawat=?) x order by x.tanggal desc,x.jam desc";
                break;
            default:
                return new ArrayList<>();
        }
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameters = category == 6 ? 3 : 1;
            for (int i = 1; i <= parameters; i++) {
                statement.setString(i, noRawat);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (category == 5) {
                        entries.add(new Entry(result.getString("tanggal"), result.getString("jam"),
                                result.getString("judul"), result.getString("isi"), true));
                    } else {
                        entries.add(new Entry(result.getString("tanggal"), result.getString("jam"),
                                result.getString("judul"), result.getString("isi")));
                    }
                }
            }
        }
        return entries;
    }

    private List<Entry> queryLaboratorium(String noRawat) throws Exception {
        List<Entry> entries = new ArrayList<>();
        List<String[]> examinations = new ArrayList<>();
        String sql = "select p.tgl_periksa,p.jam,p.kd_jenis_prw,p.kategori,"
                + "j.nm_perawatan,coalesce(d.nm_dokter,'') as dokter "
                + "from periksa_lab p inner join jns_perawatan_lab j on p.kd_jenis_prw=j.kd_jenis_prw "
                + "left join dokter d on p.kd_dokter=d.kd_dokter "
                + "where p.no_rawat=? order by p.tgl_periksa desc,p.jam desc,p.kd_jenis_prw";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, noRawat);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    examinations.add(new String[]{result.getString("tgl_periksa"), result.getString("jam"),
                        result.getString("kd_jenis_prw"), result.getString("kategori"),
                        result.getString("nm_perawatan"), result.getString("dokter")});
                }
            }
        }
        String detailSql = "select t.Pemeriksaan,d.nilai,t.satuan,d.nilai_rujukan,d.keterangan "
                + "from detail_periksa_lab d inner join template_laboratorium t on d.id_template=t.id_template "
                + "where d.no_rawat=? and d.kd_jenis_prw=? and d.tgl_periksa=? and d.jam=? order by t.urut";
        for (String[] examination : examinations) {
            List<String[]> details = new ArrayList<>();
            try (PreparedStatement detailStatement = connection.prepareStatement(detailSql)) {
                detailStatement.setString(1, noRawat);
                detailStatement.setString(2, examination[2]);
                detailStatement.setString(3, examination[0]);
                detailStatement.setString(4, examination[1]);
                try (ResultSet detailResult = detailStatement.executeQuery()) {
                    while (detailResult.next()) {
                        details.add(new String[]{detailResult.getString("Pemeriksaan"),
                            detailResult.getString("nilai"), detailResult.getString("satuan"),
                            detailResult.getString("nilai_rujukan"), detailResult.getString("keterangan")});
                    }
                }
            }
            entries.add(new Entry(examination[0], examination[1], value(examination[4]),
                    examination[2], value(examination[5]), value(examination[3]), details));
        }
        return entries;
    }

    private List<Entry> queryRadiologi(String noRawat) throws Exception {
        List<Entry> entries = new ArrayList<>();
        List<String[]> reports = new ArrayList<>();
        String sql = "select r.tgl_periksa,r.jam,r.hasil from hasil_radiologi r "
                + "where r.no_rawat=? order by r.tgl_periksa desc,r.jam desc";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, noRawat);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reports.add(new String[]{result.getString("tgl_periksa"), result.getString("jam"),
                        result.getString("hasil")});
                }
            }
        }
        String imageSql = "select lokasi_gambar from gambar_radiologi "
                + "where no_rawat=? and tgl_periksa=? and jam=? order by lokasi_gambar";
        for (String[] report : reports) {
            List<String> images = new ArrayList<>();
            try (PreparedStatement imageStatement = connection.prepareStatement(imageSql)) {
                imageStatement.setString(1, noRawat);
                imageStatement.setString(2, report[0]);
                imageStatement.setString(3, report[1]);
                try (ResultSet imageResult = imageStatement.executeQuery()) {
                    while (imageResult.next()) {
                        String location = imageResult.getString("lokasi_gambar");
                        if (location != null && !location.trim().isEmpty()) {
                            images.add(urlGambarRadiologi(location.trim()));
                        }
                    }
                }
            }
            entries.add(new Entry(report[0], report[1], "Hasil Radiologi", report[2], images));
        }
        return entries;
    }

    private String urlGambarRadiologi(String location) {
        String folder = koneksiDB.HYBRIDWEB();
        String host = koneksiDB.HOSTHYBRIDWEB();
        String path = location.replace("\\", "/").replace(" ", "%20");
        return "http://" + host + ":" + koneksiDB.PORTWEB() + "/" + folder
                + "/radiologi/" + path;
    }

    private List<Entry> queryResep(String noRawat) throws Exception {
        List<Entry> entries = new ArrayList<>();
        String sql = "select r.no_resep,r.tgl_peresepan,r.jam_peresepan,r.tgl_perawatan,r.jam,"
                + "r.kd_dokter,coalesce(d.nm_dokter,'') as dokter,r.status,r.tgl_penyerahan,r.jam_penyerahan "
                + "from resep_obat r left join dokter d on r.kd_dokter=d.kd_dokter "
                + "where r.no_rawat=? order by coalesce(nullif(r.tgl_peresepan,'0000-00-00'),r.tgl_perawatan) desc,"
                + "coalesce(r.jam_peresepan,r.jam) desc,r.no_resep desc";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, noRawat);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String noResep = result.getString("no_resep");
                    String tanggal = result.getString("tgl_peresepan");
                    if (tanggal == null || tanggal.startsWith("0000")) {
                        tanggal = result.getString("tgl_perawatan");
                    }
                    String jam = result.getString("jam_peresepan");
                    if (jam == null || jam.trim().isEmpty()) {
                        jam = result.getString("jam");
                    }
                    String doctor = value(result.getString("dokter"));
                    String status = value(result.getString("status"));
                    String tglSerah = result.getString("tgl_penyerahan");
                    String jamSerah = result.getString("jam_penyerahan");
                    String penyerahan = tglSerah == null || tglSerah.startsWith("0000")
                            ? "" : tglSerah + " " + value(jamSerah);
                    entries.add(detailResep(noResep, noRawat, tanggal, jam,
                            result.getString("tgl_perawatan"), result.getString("jam"),
                            doctor, status, penyerahan));
                }
            }
        }
        return entries;
    }

    private Entry detailResep(String noResep, String noRawat, String tanggal, String jam,
            String tanggalRawat, String jamRawat, String doctor, String status,
            String penyerahan) throws Exception {
        List<String[]> prescriptionItems = new ArrayList<>();
        List<String[]> compoundItems = new ArrayList<>();
        List<String[]> issuedItems = new ArrayList<>();
        String sqlBiasa = "select b.nama_brng,r.jml,coalesce(s.satuan,b.kode_sat) as satuan,"
                + "coalesce(r.aturan_pakai,'') as aturan from resep_dokter r "
                + "inner join databarang b on r.kode_brng=b.kode_brng "
                + "left join kodesatuan s on b.kode_sat=s.kode_sat where r.no_resep=? order by b.nama_brng";
        try (PreparedStatement statement = connection.prepareStatement(sqlBiasa)) {
            statement.setString(1, noResep);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    prescriptionItems.add(new String[]{result.getString("nama_brng"),
                        value(result.getString("jml")) + " " + value(result.getString("satuan")),
                        value(result.getString("aturan"))});
                }
            }
        }

        String sqlRacikan = "select r.no_racik,r.nama_racik,m.nm_racik,r.jml_dr,r.aturan_pakai,r.keterangan "
                + "from resep_dokter_racikan r inner join metode_racik m on r.kd_racik=m.kd_racik "
                + "where r.no_resep=? order by r.no_racik";
        try (PreparedStatement statement = connection.prepareStatement(sqlRacikan)) {
            statement.setString(1, noResep);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    StringBuilder components = new StringBuilder();
                    String sqlKomponen = "select b.nama_brng,d.jml,d.kandungan from resep_dokter_racikan_detail d "
                            + "inner join databarang b on d.kode_brng=b.kode_brng "
                            + "where d.no_resep=? and d.no_racik=? order by b.nama_brng";
                    try (PreparedStatement komponen = connection.prepareStatement(sqlKomponen)) {
                        komponen.setString(1, noResep);
                        komponen.setString(2, result.getString("no_racik"));
                        try (ResultSet bahan = komponen.executeQuery()) {
                            while (bahan.next()) {
                                if (components.length() > 0) {
                                    components.append("; ");
                                }
                                components.append(bahan.getString("nama_brng"))
                                        .append(" x ").append(bahan.getString("jml"));
                                String kandungan = bahan.getString("kandungan");
                                if (kandungan != null && !kandungan.trim().isEmpty()) {
                                    components.append(" (").append(kandungan).append(")");
                                }
                            }
                        }
                    }
                    compoundItems.add(new String[]{
                        "Racikan " + result.getString("no_racik") + ": " + result.getString("nama_racik")
                            + " (" + result.getString("nm_racik") + ")",
                        value(result.getString("jml_dr")), value(result.getString("aturan_pakai")),
                        components.toString(), value(result.getString("keterangan"))
                    });
                }
            }
        }
        String sqlDiberikan = "select b.nama_brng,d.jml,coalesce(s.satuan,b.kode_sat) as satuan,d.total,"
                + "coalesce(a.aturan,'') as aturan from detail_pemberian_obat d "
                + "inner join databarang b on d.kode_brng=b.kode_brng "
                + "left join kodesatuan s on b.kode_sat=s.kode_sat "
                + "left join aturan_pakai a on a.no_rawat=d.no_rawat and a.tgl_perawatan=d.tgl_perawatan "
                + "and a.jam=d.jam and a.kode_brng=d.kode_brng "
                + "where d.no_rawat=? and d.tgl_perawatan=? and d.jam=? order by b.nama_brng";
        try (PreparedStatement statement = connection.prepareStatement(sqlDiberikan)) {
            statement.setString(1, noRawat);
            statement.setString(2, tanggalRawat);
            statement.setString(3, jamRawat);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    issuedItems.add(new String[]{result.getString("nama_brng"),
                        value(result.getString("jml")) + " " + value(result.getString("satuan")),
                        value(result.getString("total")), value(result.getString("aturan"))});
                }
            }
        }
        return new Entry(tanggal, jam, "No. Resep: " + noResep, "", noResep,
                doctor, status, penyerahan, prescriptionItems, compoundItems,
                issuedItems, true);
    }

    private String render(List<Entry> entries, int category) {
        if (entries.isEmpty()) {
            return message("Belum ada data " + TAB_NAMES[category].toLowerCase() + " untuk No. Rawat ini.");
        }
        StringBuilder html = new StringBuilder("<html><body style='font-family:Tahoma,Arial;font-size:10pt;color:#34483e'>");
        html.append("<p style='color:#78877f;font-size:9pt'>Menampilkan seluruh data pada No. Rawat ini: ")
                .append(entries.size()).append(" catatan.</p>");
        for (Entry entry : entries) {
            if (entry.prescription) {
                appendPrescriptionCard(html, entry);
                continue;
            }
            if (entry.labExam) {
                appendLabCard(html, entry);
                continue;
            }
            if (entry.operation) {
                appendOperationCard(html, entry);
                continue;
            }
            if (entry.radiology) {
                appendRadiologyCard(html, entry);
                continue;
            }
            html.append("<div style='border:1px solid #dce8e1;border-left:3px solid #3d9b6b;padding:8px;margin:7px 0;background:#fff'>")
                    .append("<b style='color:#275a43'>").append(escape(entry.date)).append(" &nbsp; ")
                    .append(escape(entry.time)).append("</b><br><b>").append(escape(entry.title))
                    .append("</b><hr style='border:0;border-top:1px solid #e6eee9'>")
                    .append("<div style='white-space:pre-wrap;line-height:1.4'>")
                    .append(escape(entry.body).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>"))
                    .append("</div></div>");
        }
        return html.append("</body></html>").toString();
    }

    private void appendRadiologyCard(StringBuilder html, Entry entry) {
        html.append("<div style='border:1px solid #dce8e1;border-left:3px solid #3d9b6b;padding:9px;margin:8px 0;background:#fff'>")
                .append("<b style='color:#275a43'>").append(escape(entry.date)).append(" &nbsp; ")
                .append(escape(entry.time)).append("</b><br><b style='font-size:11pt'>Hasil Radiologi</b>")
                .append("<hr style='border:0;border-top:1px solid #e6eee9'>")
                .append("<div style='white-space:pre-wrap;line-height:1.4'>")
                .append(escape(entry.body).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>"))
                .append("</div>");
        if (!entry.radiologyImages.isEmpty()) {
            html.append("<div style='margin-top:9px;padding-top:7px;border-top:1px solid #e6eee9'><b>Gambar Radiologi</b><br>");
            for (String imageUrl : entry.radiologyImages) {
                String safeUrl = escape(imageUrl);
                html.append("<div style='margin:7px 0 12px'>")
                        .append("<a href='").append(safeUrl).append("'><img src='").append(safeUrl)
                        .append("' width='300' alt='Gambar radiologi, klik untuk membuka ukuran penuh'></a><br>")
                        .append("<a href='").append(safeUrl).append("'>Buka gambar ukuran penuh di browser</a>")
                        .append("</div>");
            }
            html.append("</div>");
        }
        html.append("</div>");
    }

    private void appendOperationCard(StringBuilder html, Entry entry) {
        String operationTime = entry.date + "T" + entry.time;
        html.append("<div style='border:1px solid #dce8e1;border-left:3px solid #3d9b6b;padding:9px;margin:8px 0;background:#fff'>")
                .append("<b style='color:#275a43'>").append(escape(entry.date)).append(" &nbsp; ")
                .append(escape(entry.time)).append("</b><br><b style='font-size:11pt'>")
                .append(escape(entry.title)).append("</b><hr style='border:0;border-top:1px solid #e6eee9'>")
                .append("<div style='line-height:1.5;white-space:normal'>")
                .append(escape(entry.body).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>"))
                .append("</div>")
                .append("<p style='margin:8px 0 0'><a href='editop:").append(escape(operationTime))
                .append("' style='display:inline-block;background:#1677b8;color:#ffffff;padding:6px 10px;text-decoration:none;font-weight:bold'>")
                .append("Edit / Ganti Laporan Operasi</a></p></div>");
    }

    private void appendLabCard(StringBuilder html, Entry entry) {
        html.append("<div style='border:1px solid #dce8e1;border-left:3px solid #3d9b6b;padding:9px;margin:8px 0;background:#fff'>")
                .append("<b style='color:#275a43'>").append(escape(entry.date)).append(" &nbsp; ")
                .append(escape(entry.time)).append("</b><br><b style='font-size:11pt'>")
                .append(escape(entry.title)).append("</b>")
                .append("<div style='color:#58685f;margin:4px 0 8px'>No. Pemeriksaan: ")
                .append(escape(entry.labCode)).append(" &nbsp; | &nbsp; Kategori: ")
                .append(escape(entry.labCategory));
        if (!entry.doctor.isEmpty()) {
            html.append(" &nbsp; | &nbsp; Dokter: ").append(escape(entry.doctor));
        }
        html.append("</div>");
        if (entry.labRows.isEmpty()) {
            html.append("<span style='color:#718078'>Belum ada rincian hasil.</span>");
        } else {
            html.append("<table width='100%' cellpadding='5' cellspacing='0' style='border-collapse:collapse'>")
                    .append("<tr style='background:#eef5f1'>")
                    .append("<th align='left' style='border:1px solid #dce8e1'>Pemeriksaan</th>")
                    .append("<th align='left' style='border:1px solid #dce8e1'>Hasil</th>")
                    .append("<th align='left' style='border:1px solid #dce8e1'>Satuan</th>")
                    .append("<th align='left' style='border:1px solid #dce8e1'>Nilai rujukan</th>")
                    .append("<th align='left' style='border:1px solid #dce8e1'>Keterangan</th></tr>");
            for (String[] row : entry.labRows) {
                String flag = row[4] == null ? "" : row[4].trim();
                String resultStyle = "";
                if ("L".equalsIgnoreCase(flag)) {
                    resultStyle = "color:#0000cc";
                } else if ("H".equalsIgnoreCase(flag)) {
                    resultStyle = "color:#cc0000";
                } else if ("T".equalsIgnoreCase(flag)) {
                    resultStyle = "color:#000000;font-weight:bold";
                }
                html.append("<tr>");
                for (int i = 0; i < row.length; i++) {
                    html.append("<td valign='top' style='border:1px solid #e3ebe6");
                    if ((i == 1 || i == 4) && !resultStyle.isEmpty()) {
                        html.append(";").append(resultStyle);
                    }
                    html.append("'>").append(escape(row[i])).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
        }
        html.append("</div>");
    }

    private void appendPrescriptionCard(StringBuilder html, Entry entry) {
        html.append("<div style='border:1px solid #dce8e1;border-left:3px solid #3d9b6b;padding:9px;margin:8px 0;background:#fff'>")
                .append("<b style='color:#275a43'>").append(escape(entry.date)).append(" &nbsp; ")
                .append(escape(entry.time)).append("</b><br><b style='font-size:11pt'>")
                .append(escape(entry.title)).append("</b>");
        html.append("<div style='color:#58685f;margin:5px 0 8px'>Dokter: ")
                .append(escape(entry.doctor));
        if (!entry.status.isEmpty()) {
            html.append(" &nbsp; | &nbsp; Status: ").append(escape(entry.status));
        }
        if (!entry.issuedAt.isEmpty()) {
            html.append(" &nbsp; | &nbsp; Diserahkan: ").append(escape(entry.issuedAt));
        }
        html.append("</div>");

        html.append("<b>Resep dokter</b>");
        appendTable(html, new String[]{"Nama obat", "Jumlah", "Aturan pakai"}, entry.prescriptionItems);
        if (!entry.compoundItems.isEmpty()) {
            html.append("<div style='margin-top:8px'><b>Racikan</b>");
            appendCompoundTable(html, entry.compoundItems);
            html.append("</div>");
        }

        html.append("<div style='margin-top:9px;padding-top:7px;border-top:1px solid #e6eee9'>");
        if (entry.issuedItems.isEmpty()) {
            html.append("<span style='color:#718078'>Belum ada data obat/BHP yang diserahkan.</span>");
        } else if (expandedIssued.contains(entry.expansionKey)) {
            html.append("<a href='issued:").append(escape(entry.expansionKey)).append("'>Sembunyikan detail penyerahan</a>");
            html.append("<div style='margin-top:5px'><b>Obat/BHP diserahkan (")
                    .append(entry.issuedItems.size()).append(" item)</b>");
            appendTable(html, new String[]{"Nama obat/BHP", "Jumlah", "Total", "Aturan pakai"}, entry.issuedItems);
            html.append("</div>");
        } else {
            html.append("<a href='issued:").append(escape(entry.expansionKey)).append("'>Tampilkan detail penyerahan (")
                    .append(entry.issuedItems.size()).append(" item)</a>");
        }
        html.append("</div></div>");
    }

    private void appendTable(StringBuilder html, String[] headings, List<String[]> rows) {
        if (rows.isEmpty()) {
            html.append("<p style='color:#718078'>Tidak ada item obat biasa.</p>");
            return;
        }
        html.append("<table width='100%' cellpadding='5' cellspacing='0' style='border-collapse:collapse;margin-top:4px'>")
                .append("<tr style='background:#eef5f1'>");
        for (String heading : headings) {
            html.append("<th align='left' style='border:1px solid #dce8e1'>").append(escape(heading)).append("</th>");
        }
        html.append("</tr>");
        for (String[] row : rows) {
            html.append("<tr>");
            for (int i = 0; i < headings.length; i++) {
                html.append("<td valign='top' style='border:1px solid #e3ebe6'>")
                        .append(escape(row.length > i ? row[i] : ""))
                        .append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    private void appendCompoundTable(StringBuilder html, List<String[]> rows) {
        html.append("<table width='100%' cellpadding='5' cellspacing='0' style='border-collapse:collapse;margin-top:4px'>")
                .append("<tr style='background:#eef5f1'><th align='left' style='border:1px solid #dce8e1'>Racikan & komponen</th>")
                .append("<th align='left' style='border:1px solid #dce8e1'>Jumlah</th>")
                .append("<th align='left' style='border:1px solid #dce8e1'>Aturan pakai</th></tr>");
        for (String[] row : rows) {
            html.append("<tr><td valign='top' style='border:1px solid #e3ebe6'><b>")
                    .append(escape(row[0])).append("</b>");
            if (!value(row[3]).isEmpty()) {
                html.append("<br><span style='color:#58685f'>Komponen: ").append(escape(row[3])).append("</span>");
            }
            if (!value(row[4]).isEmpty()) {
                html.append("<br><span style='color:#58685f'>Keterangan: ").append(escape(row[4])).append("</span>");
            }
            html.append("</td><td valign='top' style='border:1px solid #e3ebe6'>").append(escape(row[1]))
                    .append("</td><td valign='top' style='border:1px solid #e3ebe6'>").append(escape(row[2]))
                    .append("</td></tr>");
        }
        html.append("</table>");
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    private String message(String text) {
        return "<html><body style='font-family:Tahoma,Arial;font-size:10pt;color:#718078;padding:8px'>"
                + escape(text) + "</body></html>";
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    public void dispose() {
        requestSequence.incrementAndGet();
        if (ownsLoader) {
            loader.shutdownNow();
        }
        ttvHistory.dispose();
    }

    private static final class Entry {
        private final String date;
        private final String time;
        private final String title;
        private final String body;
        private final boolean prescription;
        private final String expansionKey;
        private final String doctor;
        private final String status;
        private final String issuedAt;
        private final List<String[]> prescriptionItems;
        private final List<String[]> compoundItems;
        private final List<String[]> issuedItems;
        private final boolean labExam;
        private final String labCode;
        private final String labCategory;
        private final List<String[]> labRows;
        private final boolean operation;
        private final boolean radiology;
        private final List<String> radiologyImages;

        private Entry(String date, String time, String title, String body) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.prescription = false;
            this.expansionKey = "";
            this.doctor = "";
            this.status = "";
            this.issuedAt = "";
            this.prescriptionItems = new ArrayList<>();
            this.compoundItems = new ArrayList<>();
            this.issuedItems = new ArrayList<>();
            this.labExam = false;
            this.labCode = "";
            this.labCategory = "";
            this.labRows = new ArrayList<>();
            this.operation = false;
            this.radiology = false;
            this.radiologyImages = new ArrayList<>();
        }

        private Entry(String date, String time, String title, String body, String expansionKey,
                String doctor, String status, String issuedAt, List<String[]> prescriptionItems,
                List<String[]> compoundItems, List<String[]> issuedItems, boolean prescription) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.expansionKey = expansionKey == null ? "" : expansionKey;
            this.doctor = doctor == null ? "" : doctor;
            this.status = status == null ? "" : status;
            this.issuedAt = issuedAt == null ? "" : issuedAt;
            this.prescriptionItems = prescriptionItems;
            this.compoundItems = compoundItems;
            this.issuedItems = issuedItems;
            this.prescription = prescription;
            this.labExam = false;
            this.labCode = "";
            this.labCategory = "";
            this.labRows = new ArrayList<>();
            this.operation = false;
            this.radiology = false;
            this.radiologyImages = new ArrayList<>();
        }

        private Entry(String date, String time, String title, String labCode, String doctor,
                String labCategory, List<String[]> labRows) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.title = title == null ? "" : title;
            this.body = "";
            this.prescription = false;
            this.expansionKey = "";
            this.doctor = doctor == null ? "" : doctor;
            this.status = "";
            this.issuedAt = "";
            this.prescriptionItems = new ArrayList<>();
            this.compoundItems = new ArrayList<>();
            this.issuedItems = new ArrayList<>();
            this.labExam = true;
            this.labCode = labCode == null ? "" : labCode;
            this.labCategory = labCategory == null ? "" : labCategory;
            this.labRows = labRows;
            this.operation = false;
            this.radiology = false;
            this.radiologyImages = new ArrayList<>();
        }

        private Entry(String date, String time, String title, String body, boolean operation) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.prescription = false;
            this.expansionKey = "";
            this.doctor = "";
            this.status = "";
            this.issuedAt = "";
            this.prescriptionItems = new ArrayList<>();
            this.compoundItems = new ArrayList<>();
            this.issuedItems = new ArrayList<>();
            this.labExam = false;
            this.labCode = "";
            this.labCategory = "";
            this.labRows = new ArrayList<>();
            this.operation = operation;
            this.radiology = false;
            this.radiologyImages = new ArrayList<>();
        }

        private Entry(String date, String time, String title, String body, List<String> radiologyImages) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.prescription = false;
            this.expansionKey = "";
            this.doctor = "";
            this.status = "";
            this.issuedAt = "";
            this.prescriptionItems = new ArrayList<>();
            this.compoundItems = new ArrayList<>();
            this.issuedItems = new ArrayList<>();
            this.labExam = false;
            this.labCode = "";
            this.labCategory = "";
            this.labRows = new ArrayList<>();
            this.operation = false;
            this.radiology = true;
            this.radiologyImages = radiologyImages;
        }
    }
}
