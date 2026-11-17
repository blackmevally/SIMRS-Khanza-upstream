package rekammedis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Second;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

/** Read-only vital-sign history for the selected encounter. */
public final class PanelRiwayatTtv extends JPanel {
    private final Connection connection;
    private final JLabel status = new JLabel("Pilih No. Rawat untuk melihat riwayat TTV.");
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final TimeSeries temperatureSeries = new TimeSeries("Suhu");
    private final TimeSeries pulseSeries = new TimeSeries("Nadi");
    private final ExecutorService loader;
    private final boolean rawatJalan;
    private final AtomicLong requestSequence = new AtomicLong();
    private volatile String activeNoRawat = "";

    public PanelRiwayatTtv(Connection connection, ExecutorService loader) {
        this(connection, loader, false);
    }

    public PanelRiwayatTtv(Connection connection, ExecutorService loader, boolean rawatJalan) {
        this.connection = connection;
        this.loader = loader;
        this.rawatJalan = rawatJalan;
        setLayout(new BorderLayout(0, 5));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(new Color(226, 234, 229)));

        String[] columns = {"Tgl. Pemeriksaan", "Jam", "Suhu (°C)", "Tensi (mmHg)",
            "Berat (Kg)", "TB (cm)", "RR (/menit)", "Nadi (/menit)",
            "SpO2 (%)", "GCS (E,V,M)", "Kesadaran"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFont(new Font("Tahoma", Font.PLAIN, 11));
        table.setRowHeight(22);
        status.setFont(new Font("Tahoma", Font.PLAIN, 10));
        status.setBorder(BorderFactory.createEmptyBorder(5, 7, 3, 5));
        add(status, BorderLayout.NORTH);

        JPanel charts = new JPanel(new GridLayout(1, 2, 6, 0));
        charts.setBackground(Color.WHITE);
        charts.add(createChart("Tren Suhu", "Suhu (°C)", temperatureSeries, new Color(213, 87, 48)));
        charts.add(createChart("Tren Nadi", "Nadi (/menit)", pulseSeries, new Color(36, 116, 177)));

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Data Pemeriksaan TTV"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, charts, tableScroll);
        split.setBorder(null);
        split.setResizeWeight(0.48);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
    }

    private ChartPanel createChart(String title, String axisLabel, TimeSeries series, Color color) {
        TimeSeriesCollection dataset = new TimeSeriesCollection(series);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Tanggal / Jam", axisLabel, dataset, false, true, false);
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(new Color(225, 232, 228));
        plot.setRangeGridlinePaint(new Color(225, 232, 228));
        plot.getRenderer().setSeriesPaint(0, color);
        ((NumberAxis) plot.getRangeAxis()).setNumberFormatOverride(new DecimalFormat("0.##"));
        DateAxis axis = (DateAxis) plot.getDomainAxis();
        axis.setDateFormatOverride(new SimpleDateFormat("dd-MM HH:mm"));
        ChartPanel panel = new ChartPanel(chart);
        panel.setPreferredSize(new java.awt.Dimension(320, 220));
        panel.setMinimumDrawWidth(220);
        panel.setMinimumDrawHeight(160);
        panel.setMaximumDrawWidth(1400);
        panel.setMaximumDrawHeight(900);
        panel.setBorder(BorderFactory.createLineBorder(new Color(226, 234, 229)));
        return panel;
    }

    public void setNoRawat(String noRawat) {
        activeNoRawat = noRawat == null ? "" : noRawat.trim();
        final long request = requestSequence.incrementAndGet();
        clearView();
        if (activeNoRawat.isEmpty()) {
            status.setText("Pilih No. Rawat untuk melihat riwayat TTV.");
            return;
        }
        final String noRawatRequest = activeNoRawat;
        status.setText("Memuat riwayat TTV untuk No. Rawat: " + noRawatRequest + "…");
        try {
            loader.submit(() -> load(noRawatRequest, request));
        } catch (RejectedExecutionException ignored) {
            // The parent dialog is closing.
        }
    }

    private void load(String noRawat, long request) {
        List<TtvRecord> records = new ArrayList<>();
        String error = null;
        String table = rawatJalan ? "pemeriksaan_ralan" : "pemeriksaan_ranap";
        String sql = "select tgl_perawatan,jam_rawat,suhu_tubuh,tensi,berat,tinggi,respirasi,nadi,spo2,gcs,kesadaran "
                + "from " + table + " where no_rawat=? order by tgl_perawatan desc,jam_rawat desc";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, noRawat);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String date = value(result.getString("tgl_perawatan"));
                    String time = value(result.getString("jam_rawat"));
                    records.add(new TtvRecord(date, time,
                            value(result.getString("suhu_tubuh")), value(result.getString("tensi")),
                            value(result.getString("berat")), value(result.getString("tinggi")),
                            value(result.getString("respirasi")), value(result.getString("nadi")),
                            value(result.getString("spo2")), value(result.getString("gcs")),
                            value(result.getString("kesadaran")), parseTimestamp(date, time)));
                }
            }
        } catch (Exception exception) {
            error = "Riwayat TTV tidak dapat dimuat. Periksa koneksi database dan struktur tabel.";
            System.out.println("Notifikasi riwayat TTV: " + exception.getClass().getSimpleName());
        }
        final List<TtvRecord> loadedRecords = records;
        final String loadError = error;
        SwingUtilities.invokeLater(() -> {
            if (requestSequence.get() == request && activeNoRawat.equals(noRawat) && !loader.isShutdown()) {
                if (loadError != null) {
                    status.setText(loadError);
                } else {
                    showRecords(loadedRecords);
                }
            }
        });
    }

    private void showRecords(List<TtvRecord> records) {
        tableModel.setRowCount(0);
        temperatureSeries.clear();
        pulseSeries.clear();
        for (TtvRecord record : records) {
            tableModel.addRow(new Object[]{record.date, record.time, record.temperature, record.pressure,
                record.weight, record.height, record.respiration, record.pulse, record.spo2,
                record.gcs, record.consciousness});
        }
        List<TtvRecord> chronological = new ArrayList<>(records);
        Collections.reverse(chronological);
        for (TtvRecord record : chronological) {
            if (record.timestamp == null) {
                continue;
            }
            Double temperature = numericValue(record.temperature);
            if (temperature != null) {
                temperatureSeries.addOrUpdate(new Second(record.timestamp), temperature);
            }
            Double pulse = numericValue(record.pulse);
            if (pulse != null) {
                pulseSeries.addOrUpdate(new Second(record.timestamp), pulse);
            }
        }
        status.setText("Menampilkan data pada No. Rawat " + activeNoRawat + ": "
                + records.size() + " catatan.");
    }

    private void clearView() {
        tableModel.setRowCount(0);
        temperatureSeries.clear();
        pulseSeries.clear();
    }

    private Timestamp parseTimestamp(String date, String time) {
        if (date.isEmpty() || time.isEmpty()) {
            return null;
        }
        try {
            return Timestamp.valueOf(date + " " + time);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Double numericValue(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(text.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    public void dispose() {
        requestSequence.incrementAndGet();
    }

    private static final class TtvRecord {
        private final String date;
        private final String time;
        private final String temperature;
        private final String pressure;
        private final String weight;
        private final String height;
        private final String respiration;
        private final String pulse;
        private final String spo2;
        private final String gcs;
        private final String consciousness;
        private final Timestamp timestamp;

        private TtvRecord(String date, String time, String temperature, String pressure, String weight,
                String height, String respiration, String pulse, String spo2, String gcs,
                String consciousness, Timestamp timestamp) {
            this.date = date;
            this.time = time;
            this.temperature = temperature;
            this.pressure = pressure;
            this.weight = weight;
            this.height = height;
            this.respiration = respiration;
            this.pulse = pulse;
            this.spo2 = spo2;
            this.gcs = gcs;
            this.consciousness = consciousness;
            this.timestamp = timestamp;
        }
    }
}
