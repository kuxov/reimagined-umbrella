package com.horstmann.violet.framework.file;


import com.horstmann.violet.framework.dialog.DialogFactory;
import com.horstmann.violet.framework.file.chooser.IFileChooserService;
import com.horstmann.violet.framework.file.export.FileExportService;
import com.horstmann.violet.framework.file.naming.ExtensionFilter;
import com.horstmann.violet.framework.file.naming.FileNamingService;
import com.horstmann.violet.framework.file.persistence.IFilePersistenceService;
import com.horstmann.violet.framework.file.persistence.IFileReader;
import com.horstmann.violet.framework.file.persistence.IFileWriter;
import com.horstmann.violet.framework.injection.bean.ManiocFramework.BeanInjector;
import com.horstmann.violet.framework.injection.bean.ManiocFramework.InjectedBean;
import com.horstmann.violet.framework.injection.resources.ResourceBundleInjector;
import com.horstmann.violet.framework.injection.resources.annotation.ResourceBundleBean;
import com.horstmann.violet.framework.printer.PrintEngine;
import com.horstmann.violet.framework.util.UniqueIDGenerator;
import com.horstmann.violet.product.diagram.abstracts.IGraph;
import com.horstmann.violet.product.diagram.abstracts.edge.IEdge;
import com.horstmann.violet.product.diagram.abstracts.node.INode;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.draw.LineSeparator;
import org.apache.commons.codec.binary.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.awt.image.BufferedImage;
import java.io.*;

import com.itextpdf.text.*;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.itextpdf.text.pdf.PdfName.FONT;
import static java.lang.Math.round;

public class GraphFile implements IGraphFile {
    private static Connection con;
    private static Statement stmt;
    private static ResultSet rs;
    /**
     * Creates a new graph file with a new graph instance
     *
     * @param graphClass
     */
    public GraphFile(Class<? extends IGraph> graphClass) {
        ResourceBundleInjector.getInjector().inject(this);
        BeanInjector.getInjector().inject(this);
        try {
            this.graph = graphClass.newInstance();
        } catch (Exception e) {
            DialogFactory.getInstance().showErrorDialog(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Constructs a graph file from an existing file
     *
     * @param file
     */
    public GraphFile(IFile file) throws IOException {
        ResourceBundleInjector.getInjector().inject(this);
        BeanInjector.getInjector().inject(this);
        IFileReader fileOpener = fileChooserService.getFileReader(file);
        if (fileOpener == null) {
            throw new IOException("Open file action cancelled by user");
        }
        InputStream in = fileOpener.getInputStream();
        if (in != null) {
            this.graph = this.filePersistenceService.read(in);
            this.currentFilename = fileOpener.getFileDefinition().getFilename();
            this.currentDirectory = fileOpener.getFileDefinition().getDirectory();
        } else {
            throw new IOException("Unable to read file " + fileOpener.getFileDefinition().getFilename() + " from location " +
                    fileOpener.getFileDefinition().getDirectory());
        }
    }

    @Override
    public IGraph getGraph() {
        return this.graph;
    }

    @Override
    public String getFilename() {
        return this.currentFilename;
    }

    @Override
    public String getDirectory() {
        return this.currentDirectory;
    }

    @Override
    public void setSaveRequired() {
        this.isSaveRequired = true;
        fireGraphModified();
    }

    @Override
    public boolean isSaveRequired() {
        return this.isSaveRequired;
    }

    /**
     * Indicates if this file is new
     *
     * @return b
     */
    private boolean isNewFile() {
        if (this.currentFilename == null && this.currentDirectory == null) {
            return true;
        }
        return false;
    }

    @Override
    public void save() {
        if (this.isNewFile()) {
            saveToNewLocation();
            return;
        }
        try {
            IFileWriter fileSaver = getFileSaver(false);
            OutputStream outputStream = fileSaver.getOutputStream();
            this.filePersistenceService.write(this.graph, outputStream);
            this.isSaveRequired = false;
            fireGraphSaved();
            this.currentFilename = fileSaver.getFileDefinition().getFilename();
            this.currentDirectory = fileSaver.getFileDefinition().getDirectory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveToNewLocation() {
        try {
            IFileWriter fileSaver = getFileSaver(true);
            if (fileSaver == null) {
                // This appends when the action is cancelled
                return;
            }
            OutputStream outputStream = fileSaver.getOutputStream();
            this.filePersistenceService.write(this.graph, outputStream);
            this.isSaveRequired = false;
            this.currentFilename = fileSaver.getFileDefinition().getFilename();
            this.currentDirectory = fileSaver.getFileDefinition().getDirectory();
            fireGraphSaved();
        } catch (IOException e1) {
            String message = MessageFormat.format(fileExportErrorMessage, e1.getMessage());
            JOptionPane.showMessageDialog(null, message, fileExportError, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Returns a IFileSaver instance. Then, this object allows to save graph content. If the graph has never been saved, the
     * FileChooserService<br/>
     * will open a dialog box to select a location. If not, the returned IFileSaver will automatically be bound to the last saving
     * location.<br/>
     * You can also force the FileChooserService to open the dialog box with the given argument.<br/>
     *
     * @param isAskedForNewLocation if true, then the FileChooser will open a dialog box to allow to choice a new location
     * @return f
     */
    private IFileWriter getFileSaver(boolean isAskedForNewLocation) {
        try {
            if (isAskedForNewLocation) {
                ExtensionFilter extensionFilter = this.fileNamingService.getExtensionFilter(this.graph);
                ExtensionFilter[] array =
                        {
                                extensionFilter
                        };
                return this.fileChooserService.chooseAndGetFileWriter(array);
            }
            return this.fileChooserService.getFileWriter(this);
        } catch (IOException e1) {
            String message = MessageFormat.format(fileExportErrorMessage, e1.getMessage());
            JOptionPane.showMessageDialog(null, message, fileExportError, JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    @Override
    public void addListener(IGraphFileListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(IGraphFileListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /**
     * Sends an event to listeners each time the graph is modified
     */
    private void fireGraphModified() {
        synchronized (listeners) {
            for (IGraphFileListener listener : listeners) {
                listener.onFileModified();
            }
        }
    }

    /**
     * Sends an event to listeners when the graph has been saved
     */
    private void fireGraphSaved() {
        synchronized (listeners) {
            for (IGraphFileListener listener : listeners) {
                listener.onFileSaved();
            }
        }
    }

    @Override
    public void exportToClipboard() {
        FileExportService.exportToclipBoard(this.graph);
        JOptionPane optionPane = new JOptionPane();
        optionPane.setIcon(this.clipBoardDialogIcon);
        optionPane.setMessage(this.clipBoardDialogMessage);
        optionPane.setName(this.clipBoardDialogTitle);
        this.dialogFactory.showDialog(optionPane, this.clipBoardDialogTitle, true);
    }

    @Override
    public void exportImage(OutputStream out, String format) {
        if (!ImageIO.getImageWritersByFormatName(format).hasNext()) {
            MessageFormat formatter = new MessageFormat(this.exportImageErrorMessage);
            String message = formatter.format(new Object[]
                    {
                            format
                    });
            JOptionPane optionPane = new JOptionPane();
            optionPane.setMessage(message);
            this.dialogFactory.showDialog(optionPane, this.exportImageDialogTitle, true);
            return;
        }
        try {

            try {
                ImageIO.write(FileExportService.getImage(this.graph), format, out);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void exportToPdf(OutputStream out) {
        FileExportService.exportToPdf(graph, out);
    }

    @Override
    public void exportToPrinter() {
        PrintEngine engine = new PrintEngine(this.graph);
        engine.start();
    }

    private float calculateScaleRatio(com.itextpdf.text.Document doc, Image image) {
        float scaleRatio;
        float imageWidth = image.getWidth();
        float imageHeight = image.getHeight();
        if (imageWidth > 0 && imageHeight > 0) {
            // Firstly get the scale ratio required to fit the image width
            Rectangle pageSize = doc.getPageSize();
            float pageWidth = pageSize.getWidth() - doc.leftMargin() - doc.rightMargin();
            scaleRatio = pageWidth / imageWidth;

            // Get scale ratio required to fit image height - if smaller, use this instead
            float pageHeight = pageSize.getHeight() - doc.topMargin() - doc.bottomMargin();
            float heightScaleRatio = pageHeight / imageHeight;
            if (heightScaleRatio < scaleRatio) {
                scaleRatio = heightScaleRatio;
            }

            // Do not upscale - if the entire image can fit in the page, leave it unscaled.
            if (scaleRatio > 1F) {
                scaleRatio = 1F;
            }
        } else {
            // No scaling if the width or height is zero.
            scaleRatio = 1F;
        }
        return scaleRatio;
    }

    @Override
    public void exportToAlpReport() throws IOException, DocumentException, SQLException {
        try {
            Class.forName("org.hsqldb.jdbc.JDBCDriver" );
        } catch (Exception e) {
            System.out.println("ERROR: failed to load HSQLDB JDBC driver.");
            e.printStackTrace();
        }

        String jdbcURL = "jdbc:hsqldb:hsql://localhost:9001/model"+graph.getId();
        String query;

        com.itextpdf.text.Document document = new com.itextpdf.text.Document();

        PdfWriter.getInstance(document, new FileOutputStream("\\MODELS\\"+graph.getTimeStamp()+"\\report.pdf"));

        document.open();

        document.add(new Paragraph("Simulation Experiment report", FontFactory.getFont(FontFactory.COURIER, 20)));

        document.add( Chunk.NEWLINE );

        // ОСНОВНАЯ ИНФА
        document.add(new Paragraph("Model - Model"+graph.getId()));

        document.add(new Paragraph("Creation date - "+graph.getTimeStamp()));

        String simTime = null;

        query = "SELECT * FROM STATISTICS_LOG " +
                "WHERE NAME = 'SimulationTime'";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                simTime = String.format("%.3f",rs.getDouble("MAXIMUM"));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }

        document.add(new Paragraph("Simulation time - from 0 to "+simTime+" seconds"));

        document.add( Chunk.NEWLINE );
        BufferedImage bufferedImage = FileExportService.getImage(this.graph);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "png", baos);
        Image iTextImage = Image.getInstance(baos.toByteArray());
        float scaleRatio = calculateScaleRatio(document, iTextImage);
        if (scaleRatio < 1F) {
            iTextImage.scalePercent(scaleRatio * 100F);
        }
        document.add(iTextImage);
        document.add( Chunk.NEWLINE );
///
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Parameters:"));
        document.add( Chunk.NEWLINE );
        PdfPTable table = new PdfPTable(new float[] { 4, 4});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Parameter");
        table.addCell("Value");
        table.setHeaderRows(1);
        PdfPCell[] cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        for (INode node : graph.getAllNodes()) {
            switch (node.getClass().getSimpleName()) {
                case ("StateNode"):
                    table.addCell("Tserv_"+node.getId());

                    table.addCell(node.getTob());
                    break;
                case ("CircularInitialStateNode"):
                    table.addCell("Lambda_"+node.getId());

                    table.addCell(node.getLambda());
                    break;
                default:
                    break;
            }
        }
        document.add(table);

///
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Histogram of the average transaction time in the system:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 2, 2, 2, 2});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Start");
        table.addCell("End");
        table.addCell("PDF");
        table.addCell("CDF");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM HISTOGRAMS_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {

                table.addCell(String.format("%.3f",rs.getDouble("START")));

                table.addCell(String.format("%.3f",rs.getDouble("END")));

                table.addCell(String.format("%.3f",rs.getDouble("PDF")));

                table.addCell(String.format("%.3f",rs.getDouble("CDF")));            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);


        document.add( Chunk.NEWLINE );
        // ИМЯ ТАБЛИЦЫ
        document.add(new Paragraph("The time characteristics of transactions in the system:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] {2, 2, 2, 2, 2});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        // ОПРЕДЕЛЕНИЕ ИМЕН СТОЛБЦОВ
        table.addCell("Mean");
        table.addCell("Deviation");
        table.addCell("Min");
        table.addCell("Max");
        table.addCell("N");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        // ЗАДАНИЕ ЦВЕТА ЗАГОЛОВКОВ
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }
        // ЗАПРОС В БАЗУ ДАННЫХ ИЗ ТАБЛИЦЫ STATISTICS_LOG
        query = "SELECT * FROM STATISTICS_LOG WHERE NAME = 'distribution'";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);
        // ДОБАВЛЕНИЕ ЗНАЧЕНИЙ В ТАБЛИЦУ
            while (rs.next()) {
                table.addCell(String.format("%.3f",rs.getDouble("MEAN")));
                table.addCell(String.format("%.3f",rs.getDouble("DEVIATION")));
                table.addCell(String.format("%.3f",rs.getDouble("MINIMUM")));
                table.addCell(String.format("%.3f",rs.getDouble("MAXIMUM")));
                table.addCell(String.valueOf(rs.getInt("NUMBER")));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        // ДОБАВЛЕНИЕ ТАБЛИЦЫ В ДОКУМЕНТ
        document.add(table);

        // ЖУРНАЛ 2
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("The aggregated statistics on time that transactions spent in blocks:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 2, 2, 2, 2, 2, 2, 2, 2 });
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Block type");
        table.addCell("Block");
        table.addCell("Activity type");
        table.addCell("Mean sec");
        table.addCell("Total sec");
        table.addCell("Min sec");
        table.addCell("Max sec");
        table.addCell("N agents");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM FLOWCHART_STATS_TIME_IN_STATE_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("BLOCK_TYPE"));
                table.addCell(rs.getString("BLOCK"));
                table.addCell(rs.getString("ACTIVITY_TYPE"));

                table.addCell(String.format("%.3f",rs.getDouble("MEAN_SECONDS")));
                table.addCell(String.format("%.3f",rs.getDouble("TOTAL_SECONDS")));
                table.addCell(String.format("%.3f",rs.getDouble("MIN_SECONDS")));
                table.addCell(String.format("%.3f",rs.getDouble("MAX_SECONDS")));

                table.addCell(String.valueOf(rs.getInt("N_AGENTS")));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        // ЖУРНАЛ 3
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Resource pool task stats:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 4, 3, 3, 2, 3});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Resource pool");
        table.addCell("Mean sec");
        table.addCell("Total sec");
        table.addCell("N tasks");
        table.addCell("output");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM RESOURCE_POOL_TASK_STATS_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("RESOURCE_POOL"));

                table.addCell(String.format("%.3f",rs.getDouble("MEAN_SECONDS")));
                table.addCell(String.format("%.3f",rs.getDouble("TOTAL_SECONDS")));

                table.addCell(String.valueOf(rs.getInt("N_TASKS")));
                table.addCell(String.format("%.3f",(1 / rs.getDouble("MEAN_SECONDS"))));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        // ЖУРНАЛ 4
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Resource pool utilization:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 4, 2, 2});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Resource pool");
        table.addCell("Utilization");
        table.addCell("Size");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM RESOURCE_POOL_UTILIZATION_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("RESOURCE_POOL"));
                table.addCell(String.format("%.3f",rs.getDouble("UTILIZATION")));
                table.addCell(String.valueOf(rs.getInt("SIZE")));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        // ЖУРНАЛ 5
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Resource unit task stats:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 4, 4, 3, 3, 3});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Resource pool");
        table.addCell("Unit");
        table.addCell("Mean sec");
        table.addCell("Total sec");
        table.addCell("N");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM RESOURCE_UNIT_TASK_STATS_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("RESOURCE_POOL"));
                table.addCell(rs.getString("UNIT"));
                table.addCell(String.format("%.3f",rs.getDouble("MEAN_SECONDS")));
                table.addCell(String.format("%.3f",rs.getDouble("TOTAL_SECONDS")));
                table.addCell(String.valueOf(rs.getInt("N_TASKS")));
            }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        // ЖУРНАЛ 6
        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Resource unit utilization:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 3, 3, 3});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Resource pool");
        table.addCell("Unit");
        table.addCell("Utilization");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM RESOURCE_UNIT_UTILIZATION_LOG";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("RESOURCE_POOL"));
                table.addCell(rs.getString("UNIT"));
                table.addCell(String.format("%.3f",rs.getDouble("UTILIZATION")));          }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        document.add( Chunk.NEWLINE );
        document.add(new Paragraph("Average queue size of the service node:"));
        document.add( Chunk.NEWLINE );
        table = new PdfPTable(new float[] { 3, 3});
        table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell("Service");
        table.addCell("Size");
        table.setHeaderRows(1);
        cells = table.getRow(0).getCells();
        for (PdfPCell cell : cells) {
            cell.setBackgroundColor(BaseColor.GREEN);
        }

        query = "SELECT * FROM STATISTICS_LOG WHERE NAME LIKE 'queueSize%'";

        try {
            con = DriverManager.getConnection(jdbcURL);
            stmt = con.createStatement();
            rs = stmt.executeQuery(query);

            while (rs.next()) {
                table.addCell(rs.getString("NAME"));
                table.addCell(String.format("%.3f",rs.getDouble("MEAN")));           }

        } catch (SQLException sqlEx) {
            sqlEx.printStackTrace();
        } finally {
            try { con.close(); } catch(SQLException se) { /*can't do anything */ }
            try { stmt.close(); } catch(SQLException se) { /*can't do anything */ }
            try { rs.close(); } catch(SQLException se) { /*can't do anything */ }
        }
        document.add(table);

        document.close();
    }

    @Override
    public void exportToAlp() throws ParserConfigurationException, IOException, SAXException, TransformerException {
        
        String modelId = UniqueIDGenerator.getNewId();
        String mainId = UniqueIDGenerator.getNewId();

        graph.setId(modelId);
        
        String initialString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!--\n" +
                "*************************************************\n" +
                "\t         AnyLogic Project File \n" +
                "*************************************************\t         \n" +
                "-->\n" +
                "<AnyLogicWorkspace WorkspaceVersion=\"1.9\" AnyLogicVersion=\"8.8.5.202310311100\" AlpVersion=\"8.8.2\">\n" +
                "<Model>\n" +
                "\t<Id>"+modelId+"</Id>\n" +
                "\t<Name><![CDATA[Model"+modelId+"]]></Name>\n" +
                "\t<EngineVersion>6</EngineVersion>\n" +
                "\t<JavaPackageName><![CDATA[model"+modelId+"]]></JavaPackageName>\n" +
                "\t<ModelTimeUnit><![CDATA[Second]]></ModelTimeUnit>\n" +
                "\t<ActiveObjectClasses>\n" +
                "\t\t<!--   =========   Active Object Class   ========  -->\n" +
                "\t\t<ActiveObjectClass>\n" +
                "\t\t\t<Id>"+mainId+"</Id>\n" +
                "\t\t\t<Name><![CDATA[Main]]></Name>\n" +
                "\t\t\t<Generic>false</Generic>\n" +
                "\t\t\t<GenericParameter>\n" +
                "\t\t\t\t<Id>1710188750150</Id>\n" +
                "\t\t\t\t<Name><![CDATA[1710188750150]]></Name>\n" +
                "\t\t\t\t<GenericParameterValue Class=\"CodeValue\">\n" +
                "\t\t\t\t\t<Code><![CDATA[T extends Agent]]></Code>\n" +
                "\t\t\t\t</GenericParameterValue>\n" +
                "\t\t\t\t<GenericParameterLabel><![CDATA[Параметр настройки:]]></GenericParameterLabel>\n" +
                "\t\t\t</GenericParameter>\n" +
                "\t\t\t<FlowChartsUsage>ENTITY</FlowChartsUsage>\n" +
                "\t\t\t<SamplesToKeep>100</SamplesToKeep>\n" +
                "\t\t\t<LimitNumberOfArrayElements>false</LimitNumberOfArrayElements>\n" +
                "\t\t\t<ElementsLimitValue>100</ElementsLimitValue>\n" +
                "\t\t\t<MakeDefaultViewArea>true</MakeDefaultViewArea>\n" +
                "\t\t\t<SceneGridColor/>\n" +
                "\t\t\t<SceneBackgroundColor/>\n" +
                "\t\t\t<SceneSkybox>null</SceneSkybox>\n" +
                "\t\t\t<AgentProperties>\n" +
                "\t\t\t\t<EnvironmentDefinesInitialLocation>true</EnvironmentDefinesInitialLocation>\n" +
                "\t\t\t\t<RotateAnimationTowardsMovement>true</RotateAnimationTowardsMovement>\n" +
                "\t\t\t\t<RotateAnimationVertically>false</RotateAnimationVertically>\n" +
                "\t\t\t\t<VelocityCode Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t<Code><![CDATA[10]]></Code>\n" +
                "\t\t\t\t\t<Unit Class=\"SpeedUnits\"><![CDATA[MPS]]></Unit>\n" +
                "\t\t\t\t</VelocityCode>\n" +
                "\t\t\t\t<PhysicalLength Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t<Unit Class=\"LengthUnits\"><![CDATA[METER]]></Unit>\n" +
                "\t\t\t\t</PhysicalLength>\n" +
                "\t\t\t\t<PhysicalWidth Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t<Unit Class=\"LengthUnits\"><![CDATA[METER]]></Unit>\n" +
                "\t\t\t\t</PhysicalWidth>\n" +
                "\t\t\t\t<PhysicalHeight Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t<Unit Class=\"LengthUnits\"><![CDATA[METER]]></Unit>\n" +
                "\t\t\t\t</PhysicalHeight>\n" +
                "\t\t\t</AgentProperties>\n" +
                "\t\t\t<EnvironmentProperties>\n" +
                "\t\t\t\t\t<EnableSteps>false</EnableSteps>\n" +
                "\t\t\t\t\t<StepDurationCode Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[1.0]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</StepDurationCode>\n" +
                "\t\t\t\t\t<SpaceType>CONTINUOUS</SpaceType>\n" +
                "\t\t\t\t\t<WidthCode><![CDATA[500]]></WidthCode>\n" +
                "\t\t\t\t\t<HeightCode><![CDATA[500]]></HeightCode>\n" +
                "\t\t\t\t\t<ZHeightCode><![CDATA[0]]></ZHeightCode>\n" +
                "\t\t\t\t\t<ColumnsCountCode><![CDATA[100]]></ColumnsCountCode>\n" +
                "\t\t\t\t\t<RowsCountCode><![CDATA[100]]></RowsCountCode>\n" +
                "\t\t\t\t\t<NeigborhoodType>MOORE</NeigborhoodType>\n" +
                "\t\t\t\t\t<LayoutType>USER_DEF</LayoutType>\n" +
                "\t\t\t\t\t<LayoutTypeApplyOnStartup>true</LayoutTypeApplyOnStartup>\n" +
                "\t\t\t\t\t<NetworkType>USER_DEF</NetworkType>\n" +
                "\t\t\t\t\t<NetworkTypeApplyOnStartup>true</NetworkTypeApplyOnStartup>\n" +
                "\t\t\t\t\t<ConnectionsPerAgentCode><![CDATA[2]]></ConnectionsPerAgentCode>\n" +
                "\t\t\t\t\t<ConnectionsRangeCode><![CDATA[50]]></ConnectionsRangeCode>\n" +
                "\t\t\t\t\t<NeighborLinkFractionCode><![CDATA[0.95]]></NeighborLinkFractionCode>\n" +
                "\t\t\t\t\t<MCode><![CDATA[10]]></MCode>\n" +
                "\t\t\t</EnvironmentProperties>\n" +
                "\t\t\t<DatasetsCreationProperties>\n" +
                "\t\t\t\t<AutoCreate>true</AutoCreate>\n" +
                "\t\t\t\t\t<OccurrenceAtTime>true</OccurrenceAtTime>\n" +
                "\t\t\t\t\t<OccurrenceDate>1710230400000</OccurrenceDate>\n" +
                "\t\t\t\t\t<OccurrenceTime Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</OccurrenceTime>\n" +
                "\t\t\t\t\t<RecurrenceCode Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</RecurrenceCode>\n" +
                "\t\t\t</DatasetsCreationProperties>\n" +
                "\t\t\t<ScaleRuler>\n" +
                "\t\t\t\t<Id>1710188750147</Id>\n" +
                "\t\t\t\t<Name><![CDATA[scale]]></Name>\n" +
                "\t\t\t\t<X>0</X><Y>-150</Y>\n" +
                "\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t<PresentationFlag>false</PresentationFlag>\n" +
                "\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t<DrawMode>SHAPE_DRAW_2D3D</DrawMode>\n" +
                "\t\t\t\t<Length>100</Length>\n" +
                "\t\t\t\t<Rotation>0</Rotation>\n" +
                "\t\t\t\t<ScaleType>BASED_ON_LENGTH</ScaleType>\n" +
                "\t\t\t\t<ModelLength>10</ModelLength>\n" +
                "\t\t\t\t<LengthUnits>METER</LengthUnits>\n" +
                "\t\t\t\t<Scale>10</Scale>\n" +
                "\t\t\t\t<InheritedFromParentAgentType>true</InheritedFromParentAgentType>\n" +
                "\t\t\t</ScaleRuler>\n" +
                "\t\t\t<CurrentLevel>1710188750151</CurrentLevel>\n" +
                "\t\t\t<ConnectionsId>1710188750145</ConnectionsId>\n" +
                "\t\t\t<Variables>\n" +
                "\t\t\t</Variables>\n" +
                "\t\t\t<Connectors>\n" +
                "\t\t\t</Connectors>\n" +
                "\t\t\t<AgentLinks>\n" +
                "\t\t\t\t<AgentLink>\n" +
                "\t\t\t\t\t<Id>1710188750145</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[connections]]></Name>\n" +
                "\t\t\t\t\t<X>50</X><Y>-50</Y>\n" +
                "\t\t\t\t\t<Label><X>15</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>true</ShowLabel>\n" +
                "\t\t\t\t\t<HandleReceiveInConnections>false</HandleReceiveInConnections>\n" +
                "\t\t\t\t\t<AgentLinkType>COLLECTION_OF_LINKS</AgentLinkType>\n" +
                "\t\t\t\t\t<AgentLinkBidirectional>true</AgentLinkBidirectional>\n" +
                "\t\t\t\t\t<MessageType><![CDATA[Object]]></MessageType>\n" +
                "\t\t\t\t\t<LineStyle>SOLID</LineStyle>\n" +
                "\t\t\t\t\t<LineWidth>1</LineWidth>\n" +
                "\t\t\t\t\t<LineColor>-16777216</LineColor>\n" +
                "\t\t\t\t\t<LineZOrder>UNDER_AGENTS</LineZOrder>\n" +
                "\t\t\t\t\t<LineArrow>NONE</LineArrow>\n" +
                "\t\t\t\t\t<LineArrowPosition>END</LineArrowPosition>\n" +
                "\t\t\t\t</AgentLink>\n" +
                "\t\t\t</AgentLinks>\n" +

                "\t\t\t<AnalysisData>\n" +
                "    \t\t\t<Statistics>\n" +
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[SimulationTime]]></Name>\n" +
                "\t\t\t\t\t<X>-500</X><Y>-100</Y>\n" +
                "\t\t\t\t\t<Label><X>15</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>true</ShowLabel>\n" +
                "\t\t\t\t\t<AutoUpdate>false</AutoUpdate>\n" +
                "\t\t\t\t\t<OccurrenceAtTime>true</OccurrenceAtTime>\n" +
                "\t\t\t\t\t<OccurrenceDate>1716451200000</OccurrenceDate>\n" +
                "\t\t\t\t\t<OccurrenceTime Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</OccurrenceTime>\n" +
                "\t\t\t\t\t<RecurrenceCode Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</RecurrenceCode>\n" +
                "\t\t\t\t\t<Discrete>false</Discrete>\n" +
                "\t\t\t\t\t<ValueCode><![CDATA[time()]]></ValueCode>\n" +
                "\t\t\t\t</Statistics>\n" +
                "\t\t\t</AnalysisData>"+

                "\n" +
                "\t\t\t<EmbeddedObjects>\n" +
                "\t\t\t</EmbeddedObjects>\n" +
                "\n" +
                "\t\t\t<Presentation>\n" +
                "\t\t\t\t<Level>\n" +
                "\t\t\t\t\t<Id>1710188750151</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[level]]></Name>\n" +
                "\t\t\t\t\t<X>0</X><Y>0</Y>\n" +
                "\t\t\t\t\t<Label><X>10</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>true</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<DrawMode>SHAPE_DRAW_2D3D</DrawMode>\n" +
                "\t\t\t\t\t<Z>0</Z>\n" +
                "\t\t\t\t\t<LevelVisibility>DIM_NON_CURRENT</LevelVisibility>\n" +
                "\n" +
                "\n" +
                "\t\t\t\t</Level>\n" +
                "\t\t\t</Presentation>\n" +
                "\n" +
                "\t\t</ActiveObjectClass>\n" +
                "\t</ActiveObjectClasses>\t\n" +
                "\t<DifferentialEquationsMethod>EULER</DifferentialEquationsMethod>\n" +
                "\t<MixedEquationsMethod>RK45_NEWTON</MixedEquationsMethod>\n" +
                "\t<AlgebraicEquationsMethod>MODIFIED_NEWTON</AlgebraicEquationsMethod>\n" +
                "\t<AbsoluteAccuracy>1.0E-5</AbsoluteAccuracy>\n" +
                "\t<FixedTimeStep>0.001</FixedTimeStep>\n" +
                "\t<RelativeAccuracy>1.0E-5</RelativeAccuracy>\n" +
                "\t<TimeAccuracy>1.0E-5</TimeAccuracy>\n" +
                "\t<Frame>\n" +
                "\t\t<Width>1000</Width>\n" +
                "\t\t<Height>600</Height>\n" +
                "\t</Frame>\n" +
                "\t<Database>\n" +
                "\t\t<Logging>true</Logging>\n" +
                "\t\t<AutoExport>false</AutoExport>\n" +
                "\t\t<ShutdownCompact>true</ShutdownCompact>\n" +
                "\t\t<ImportSettings>\n" +
                "\t\t</ImportSettings>\n" +
                "\t\t<ExportSettings>\n" +
                "\t\t</ExportSettings>\n" +
                "\t</Database>" +
                "\t\n" +
                "\t<RunConfiguration ActiveObjectClassId=\""+mainId+"\">\n" +
                "\t\t<Id>1710188750160</Id>\n" +
                "\t\t<Name><![CDATA[RunConfiguration]]></Name>\n" +
                "\t\t<MaximumMemory>512</MaximumMemory>\n" +
                "\t\t<ModelTimeProperties>\n" +
                "\t\t\t<StopOption><![CDATA[Stop at specified time]]></StopOption>\n" +
                "\t\t\t<InitialDate><![CDATA[1710115200000]]></InitialDate>\t\n" +
                "\t\t\t<InitialTime><![CDATA[0.0]]></InitialTime>\t\n" +
                "\t\t\t<FinalDate><![CDATA[1712793600000]]></FinalDate>\t\n" +
                "\t\t\t<FinalTime><![CDATA[100.0]]></FinalTime>\t\n" +
                "\t\t</ModelTimeProperties>\n" +
                "\t\t<AnimationProperties>\n" +
                "\t\t\t<StopNever>true</StopNever>\n" +
                "\t\t\t<ExecutionMode>realTimeScaled</ExecutionMode>\n" +
                "\t\t\t<RealTimeScale>1.0</RealTimeScale>\n" +
                "\t\t\t<EnableZoomAndPanning>true</EnableZoomAndPanning>\n" +
                "\t\t\t<EnableDeveloperPanel>false</EnableDeveloperPanel>\n" +
                "\t\t\t<ShowDeveloperPanelOnStart>false</ShowDeveloperPanelOnStart>\n" +
                "\t\t</AnimationProperties>\n" +
                "\t\t<Inputs>\t\t\n" +
                "\t\t</Inputs>\n" +
                "\t\t<Outputs>\n" +
                "\t\t</Outputs>\n" +
                "\t</RunConfiguration>\n" +
                "\t<Experiments>\t\n" +
                "\t\t<!--   =========   Simulation Experiment   ========  -->\n" +
                "\t\t<SimulationExperiment ActiveObjectClassId=\""+mainId+"\">\n" +
                "\t\t\t<Id>1710188750157</Id>\n" +
                "\t\t\t<Name><![CDATA[Simulation]]></Name>\n" +
                "\t\t\t<CommandLineArguments><![CDATA[]]></CommandLineArguments>\n" +
                "\t\t\t<MaximumMemory>512</MaximumMemory>\n" +
                "\t\t\t<RandomNumberGenerationType>fixedSeed</RandomNumberGenerationType>\n" +
                "\t\t\t<CustomGeneratorCode>new Random()</CustomGeneratorCode>\n" +
                "\t\t\t<SeedValue>1</SeedValue>\n" +
                "\t\t\t<SelectionModeForSimultaneousEvents>LIFO</SelectionModeForSimultaneousEvents>\n" +
                "\t\t\t<VmArgs><![CDATA[]]></VmArgs>\n" +
                "\t\t\t<LoadRootFromSnapshot>false</LoadRootFromSnapshot>\n" +
                "\n" +
                "\t\t\t<Presentation>\n" +
                "\t\t\t\t<Text>\n" +
                "\t\t\t\t\t<Id>1710188750159</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[text]]></Name>\n" +
                "\t\t\t\t\t<X>50</X><Y>30</Y>\n" +
                "\t\t\t\t\t<Label><X>10</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>true</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<DrawMode>SHAPE_DRAW_2D3D</DrawMode>\n" +
                "\t\t\t\t\t<EmbeddedIcon>false</EmbeddedIcon>\n" +
                "\t\t\t\t\t<Z>0</Z>\n" +
                "\t\t\t\t\t<Rotation>0.0</Rotation>\n" +
                "\t\t\t\t\t<Color>-12490271</Color>\n" +
                "\t\t\t\t\t<Text><![CDATA[Model1]]></Text>\n" +
                "\t\t\t\t\t<Font>\n" +
                "\t\t\t\t\t\t<Name>SansSerif</Name>\n" +
                "\t\t\t\t\t\t<Size>24</Size>\n" +
                "\t\t\t\t\t\t<Style>0</Style>\n" +
                "\t\t\t\t\t</Font>\n" +
                "\t\t\t\t\t<Alignment>LEFT</Alignment>\n" +
                "\t\t\t\t</Text>\n" +
                "\t\t\t</Presentation>\n" +
                "\n" +
                "\t\t\t<Parameters>\t\t\t\n" +
                "\t\t\t</Parameters>\t\t\t\n" +
                "\t\t\t<PresentationProperties>\n" +
                "\t\t\t\t<EnableZoomAndPanning>true</EnableZoomAndPanning>\n" +
                "\t\t\t\t<ExecutionMode><![CDATA[realTimeScaled]]></ExecutionMode>\n" +
                "\t\t\t\t<Title><![CDATA[Model : Simulation]]></Title>\t\n" +
                "\t\t\t\t<EnableDeveloperPanel>true</EnableDeveloperPanel>\n" +
                "\t\t\t\t<ShowDeveloperPanelOnStart>false</ShowDeveloperPanelOnStart>\n" +
                "\t\t\t\t<RealTimeScale>1.0</RealTimeScale>\n" +
                "\t\t\t</PresentationProperties>\n" +
                "\t\t\t<ModelTimeProperties>\n" +
                "\t\t\t\t<StopOption><![CDATA[Never]]></StopOption>\n" +
                "\t\t\t\t<InitialDate><![CDATA[1710115200000]]></InitialDate>\t\n" +
                "\t\t\t\t<InitialTime><![CDATA[0.0]]></InitialTime>\t\n" +
                "\t\t\t\t<FinalDate><![CDATA[1712793600000]]></FinalDate>\t\n" +
                "\t\t\t\t<FinalTime><![CDATA[100.0]]></FinalTime>\t\n" +
                "\t\t\t</ModelTimeProperties>\n" +
                "\t\t\t<BypassInitialScreen>true</BypassInitialScreen>\n" +
                "\t\t</SimulationExperiment>\t\n" +
                "\t</Experiments>\n" +
                "\t<ModelResources>\n" +
                "\t\t<Resource>\n" +
                "\t\t\t<Path><![CDATA[test.xlsx]]></Path>\n" +
                "\t\t\t<ReferencedFromUserCode>false</ReferencedFromUserCode>\n" +
                "\t\t\t<Location>FILE_SYSTEM</Location>\n" +
                "\t\t</Resource>\n" +
                "\t</ModelResources>" +
                "    <RequiredLibraryReference>\n" +
                "\t\t<LibraryName><![CDATA[com.anylogic.libraries.modules.markup_descriptors]]></LibraryName>\n" +
                "\t\t<VersionMajor>1</VersionMajor>\n" +
                "\t\t<VersionMinor>0</VersionMinor>\n" +
                "\t\t<VersionBuild>0</VersionBuild>\n" +
                "    </RequiredLibraryReference>\n" +
                "    <RequiredLibraryReference>\n" +
                "\t\t<LibraryName><![CDATA[com.anylogic.libraries.processmodeling]]></LibraryName>\n" +
                "\t\t<VersionMajor>8</VersionMajor>\n" +
                "\t\t<VersionMinor>0</VersionMinor>\n" +
                "\t\t<VersionBuild>5</VersionBuild>\n" +
                "    </RequiredLibraryReference>\n" +
                "</Model>\n" +
                "</AnyLogicWorkspace>";
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(initialString)));

        if (graph.getAllNodes() == null){return;} else {

            for (INode node : graph.getAllNodes()) {
                switch (node.getClass().getSimpleName()) {
                    case ("StateNode"):
                        appendAlpWork(node, doc, builder);
                        break;
                    case ("CircularInitialStateNode"):
                        appendAlpStart(node, doc, builder);
                        break;
                    case ("CircularFinalStateNode"):
                        appendAlpEnd(node, doc, builder);
                        break;
                    case ("ProbabilityNode"):
                        appendProbabilityBlock(node, doc, builder);
                        break;
                    case ("SplitNode"):
                        appendSplitBlock(node, doc, builder);
                        break;
                    case ("CombineNode"):
                        appendCombineBlock(node, doc, builder);
                        break;
                    default:
                        break;
                }
            }

            for (INode node : graph.getAllNodes()) {
                node.setFlag(false);
            }

            for (IEdge edge : graph.getAllEdges()) {
                connectBlocks(edge, doc, builder, modelId);
            }

        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        DOMSource source = new DOMSource(doc);

        graph.setTimeStamp(new SimpleDateFormat("dd_MM_yyyy hh_mm_ss").format(new java.util.Date()));
        new File("\\MODELS\\"+graph.getTimeStamp()).mkdirs();
        StreamResult file = new StreamResult(new File("\\MODELS\\"+graph.getTimeStamp()+"\\Model" + modelId + ".alp"));
        transformer.transform(source, file);
    }

    private IGraph graph;

    /**
     * Needed to identify the physical file used to save the graph
     */
    private String currentFilename;
    private String autoSaveFilename;

    /**
     * Needed to identify the physical file used to save the graph
     */
    private String currentDirectory;
    private final String autoSaveDirectory = System.getProperty("user.home") + File.separator + "VioletUML" + File.separator;

    private boolean isSaveRequired = false;

    @ResourceBundleBean(key = "dialog.export_to_clipboard.icon")
    private ImageIcon clipBoardDialogIcon;

    @ResourceBundleBean(key = "dialog.export_to_clipboard.title")
    private String clipBoardDialogTitle;

    @ResourceBundleBean(key = "dialog.export_to_clipboard.ok")
    private String clipBoardDialogMessage;

    @ResourceBundleBean(key = "dialog.error.unsupported_image")
    private String exportImageErrorMessage;

    @ResourceBundleBean(key = "dialog.error.title")
    private String exportImageDialogTitle;

    @InjectedBean
    private IFileChooserService fileChooserService;

    @InjectedBean
    private FileNamingService fileNamingService;

    @InjectedBean
    private IFilePersistenceService filePersistenceService;

    @ResourceBundleBean(key = "file.export.error.message")
    private String fileExportErrorMessage;

    @ResourceBundleBean(key = "file.export.error")
    private String fileExportError;

    @InjectedBean
    private DialogFactory dialogFactory;

    private List<IGraphFileListener> listeners = new ArrayList<IGraphFileListener>();

    /*----------------------------------------------------------------------------------------------*/

    public void connectBlocks(IEdge edge, Document d, DocumentBuilder b, String mid) throws IOException, SAXException {
        String startTargetItemName = null;
        String startSourceClassName = null;
        String startSourceItemName = null;

        String endTargetItemName = null;
        String endSourceClassName = null;
        String endSourceItemName = null;

        int shiftStart = 0;
        int shiftEnd = 0;
        int shiftDown = 0;
        int shiftUp = 0;

        switch (edge.getStart().getClass().getSimpleName()) {
            case ("CircularInitialStateNode"):
                startTargetItemName = "timeMeasureStart";
                startSourceClassName = "TimeMeasureStart";
                startSourceItemName = "out";
                shiftStart = 5;
                shiftDown = -2;
                break;
            case ("StateNode"):
                startTargetItemName = "service_" + edge.getStart().getId();
                startSourceClassName = "Service";
                startSourceItemName = "out";
                shiftStart = 20;
                break;
            case ("ProbabilityNode"):
                startTargetItemName = "selectOutput_" + edge.getStart().getId();
                startSourceClassName = "SelectOutput";

                if (!edge.getStart().getFlag()) {
                    startSourceItemName = "outT";
                    edge.getStart().setFlag();
                    shiftStart = 10;
                } else {
                    startSourceItemName = "outF";
                    shiftDown = -10;
                }
                break;
            case ("SplitNode"):
                startTargetItemName = "split_" + edge.getStart().getId();
                startSourceClassName = "Split";

                if (!edge.getStart().getFlag()) {
                    startSourceItemName = "out";
                    edge.getStart().setFlag();
                    shiftStart = 10;
                    shiftDown = -10;
                } else {
                    startSourceItemName = "outCopy";
                    shiftStart = 10;
                    shiftDown = 10;
                }
                break;
            case ("CombineNode")://
                startTargetItemName = "combine_" + edge.getStart().getId();
                startSourceClassName = "Combine";
                startSourceItemName = "out";
                shiftStart = 10;
                shiftDown = -10;
                break;
            default:
                break;
        }

        switch (edge.getEnd().getClass().getSimpleName()) {
            case ("CircularFinalStateNode"):
                endTargetItemName = "timeMeasureEnd";
                endSourceClassName = "TimeMeasureEnd";
                endSourceItemName = "in";
                shiftEnd = 0;
                shiftUp = 5;
                break;
            case ("StateNode"):
                endTargetItemName = "service_" + edge.getEnd().getId();
                endSourceClassName = "Service";
                endSourceItemName = "in";
                shiftEnd = -20;
                break;
            case ("ProbabilityNode"):
                endTargetItemName = "selectOutput_" + edge.getEnd().getId();
                endSourceClassName = "SelectOutput";
                endSourceItemName = "in";
                shiftEnd = -10;
                break;
            case ("SplitNode"):
                endTargetItemName = "split_" + edge.getEnd().getId();
                endSourceClassName = "Split";
                endSourceItemName = "in";
                shiftEnd = -10;
                shiftUp = -10;
                break;
            case ("CombineNode")://
                endTargetItemName = "combine_" + edge.getEnd().getId();
                endSourceClassName = "Combine";
                if (!edge.getEnd().getFlag()) {
                    endSourceItemName = "in1";
                    edge.getEnd().setFlag();
                    shiftEnd = -10;
                    shiftUp = -10;
                } else {
                    endSourceItemName = "in2";
                    edge.getEnd().setFlag();
                    shiftEnd = -10;
                    shiftUp = 10;
                }
                break;
            default:
                break;
        }

        String s = "\t\t\t\t<Connector>\n" +
                "\t\t\t\t\t<Id>" + UniqueIDGenerator.getNewId() + "</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[connector_" + UniqueIDGenerator.getNewId() + "]]></Name>\n" +
                "\t\t\t\t\t<X>90</X><Y>-140</Y>\n" +
                "\t\t\t\t\t<Label><X>10</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model" + mid + "]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[" + startTargetItemName + "]]></ItemName>\n" +
                "\t\t\t\t\t</SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<SourceConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[" + startSourceClassName + "]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[" + startSourceItemName + "]]></ItemName>\n" +
                "\t\t\t\t\t</SourceConnectableItemReference>\n" +
                "\t\t\t\t\t<TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model" + mid + "]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[" + endTargetItemName + "]]></ItemName>\n" +
                "\t\t\t\t\t</TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<TargetConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[" + endSourceClassName + "]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[" + endSourceItemName + "]]></ItemName>\n" +
                "\t\t\t\t\t</TargetConnectableItemReference>\n" +
                "\t\t\t\t\t<Points>\n" +
                "\t\t\t\t\t\t<Point><X>" + (edge.getStart().getLocation().getX() + 40 + shiftStart) + "</X><Y>" + (edge.getStart().getLocation().getY() + shiftDown) + "</Y></Point>\n" +
                "\t\t\t\t\t\t<Point><X>" + (edge.getEnd().getLocation().getX() + 40 + shiftEnd) + "</X><Y>" + (edge.getStart().getLocation().getY() + shiftUp) + "</Y></Point>\n" +
                "\t\t\t\t\t</Points>\n" +
                "\t\t\t\t</Connector>";

        appendXmlFragment(b, d.getElementsByTagName("Connectors").item(0), s);
    }

    public void appendCombineBlock(INode node, Document d, DocumentBuilder b) throws IOException, SAXException {
        String s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>"+node.getId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[combine_"+node.getId()+"]]></Name>\n" +
                "\t\t\t\t\t<X>"+node.getLocation().getX()+"</X><Y>"+node.getLocation().getY()+"</Y>\n" +
                "\t\t\t\t\t<Label><X>-15</X><Y>-15</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Combine]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Combine]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242935]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Combine]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242936]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Combine]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242937]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[combineMode]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[self.ENTITY1]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[newEntity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[changeDimensions]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[length]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[width]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[height]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entityLocation1]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entityLocation2]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entityLocation]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[addToCustomPopulation]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[population]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[pushProtocol]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[restoreEntityLocationOnExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter1]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter2]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);
    }
    public void appendSplitBlock(INode node, Document d, DocumentBuilder b)throws IOException, SAXException {
        String s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>"+node.getId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[split_"+node.getId()+"]]></Name>\n" +
                "\t\t\t\t\t<X>"+node.getLocation().getX()+"</X><Y>"+node.getLocation().getY()+"</Y>\n" +
                "\t\t\t\t\t<Label><X>-10</X><Y>-20</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Split]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Split]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242938]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Split]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242939]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[numberOfCopies]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[newEntity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[changeDimensions]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[length]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[width]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[height]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationX]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationY]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationZ]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLatitude]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLongitude]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationGeoPlaceName]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationXYZInNetwork]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationNetwork]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLevel]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationNode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationAttractor]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[speed]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[addToCustomPopulation]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[population]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onAtEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExitCopy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);
    }

    public void appendProbabilityBlock(INode node, Document d, DocumentBuilder b)throws IOException, SAXException {
        String s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>selectOutput_"+node.getId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[selectOutput_"+node.getId()+"]]></Name>\n" +
                "\t\t\t\t\t<X>"+node.getLocation().getX()+"</X><Y>"+node.getLocation().getY()+"</Y>\n" +
                "\t\t\t\t\t<Label><X>"+node.getLocation().getX()+"</X><Y>"+(node.getLocation().getY()+20)+"</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[SelectOutput]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[SelectOutput]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242931]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[conditionIsProbabilistic]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[condition]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[probability]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA["+node.getProbability()+"]]></Code>\n" +   //ВЕРОЯТНОСТЬ
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onAtEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExitTrue]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExitFalse]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);
    }

    public void appendAlpWork(INode node, Document d, DocumentBuilder b) throws IOException, SAXException {
        int count = d.getElementsByTagName("Statistics").getLength();

        // ДОБАВЛЕНИЕ ЭЛЕМЕНТА RESOURCE POOL (НАБОР КАНАЛОВ ДЛЯ УЗЛА ОБСЛУЖИВАНИЯ)
        String s = "\t\t\t\t<EmbeddedObject>\n" +
                // ГЕНЕРИРУЕМ УНИКАЛЬНЫЙ ИДЕНТИФИКАТОР ЭЛЕМЕНТА
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                //  ОПРЕДЕЛЕЯЕМ ИМЯ БЛОКА
                "\t\t\t\t\t<Name><![CDATA[resourcePool_"+node.getId()+"]]></Name>\n" +
                // РАСПОЛОЖЕНИЕ НА ГРАФЕ В СТОЛБЦЕ
                "\t\t\t\t\t<X>-50</X><Y>"+(count * 45 )+"</Y>\n" +
                "\t\t\t\t\t<Label><X>-30</X><Y>-20</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>true</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[ResourcePool]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[ResourcePool]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336243135]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[type]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[self.RESOURCE_STATIC]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacityDefinitionType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacity]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                // ОПРЕДЕЛЕНИЕ ЧИСЛА КАНАЛОВ ОБСЛУЖИВАНИЯ
                "\t\t\t\t\t\t\t\t<Code><![CDATA["+node.getWorkers()+"]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacityBasedOnAttractors]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacitySchedule]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacityScheduleOnOff]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[capacityOnValue]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[shiftGroupSchedules]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[shiftGroupSizes]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[shiftGroupsPlan]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[destroyExcessUnits]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[newUnit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[speed]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[homeLocationType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[homeNode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[homeNodes]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[showDefaultAnimationStatic]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[downtimeSource]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceProfile]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[downtimeList]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableMaintenance]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[initialTimeToMaintenance]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[timeBetweenMaintenances]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceTaskPriority]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceTaskMayPreemptOtherTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceTaskStart]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maintenanceUsageState]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableFailuresRepairs]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[initialTimeToFailure]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[timeBetweenFailures]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[countBusyOnlyTimeToFailure]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[repairType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[repairTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[repairTaskStart]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[repairUsageState]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableBreaks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[breaksSchedule]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[breakTaskPriority]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[breakTaskMayPreemptOtherTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[breakTaskPreemptionPolicy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[breakUsageState]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableCustomTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[customTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[endOfShiftTaskPriority]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[endOfShiftTaskMayPreemptOtherTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[endOfShiftTaskPreemptionPolicy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[customizeRequestChoice]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[requestChoiceCondition]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[addToCustomPopulation]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[population]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[forceStatisticsCollection]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onNewUnit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onDestroyUnit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onSeize]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onRelease]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onWrapUp]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onUnitStateChange]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onMaintenanceStart]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onMaintenanceEnd]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onFailure]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onRepair]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onBreakStart]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onBreakEnd]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onBreakTerminated]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";
        // ДОБАВЛЕНИЕ ФРАГМЕНТА КОДА ВЫШЕ В XML ДОКУМЕНТ
        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);

        // ДОБАВЛЕНИЕ ЭЛЕМЕНТА СТАТИСТИКИ ДЛЯ СБОРА ИНФОРМАЦИИ О РАЗМЕРЕ ОЧЕРЕДИ ДАННОГО УЗЛА
        s = "    \t\t\t<Statistics>\n" +
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[queueSize_"+node.getId()+"]]></Name>\n" +
                "\t\t\t\t\t<X>-300</X><Y>"+ ((count * 20 ) + 30) +"</Y>\n" +
                "\t\t\t\t\t<Label><X>15</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>true</ShowLabel>\n" +
                // НЕ ОБНОВЛЯЕТ ЗНАЧЕНИЯ АВТОМАТИЧЕСКИ
                "\t\t\t\t\t<AutoUpdate>false</AutoUpdate>\n" +
                "\t\t\t\t\t<OccurrenceAtTime>true</OccurrenceAtTime>\n" +
                "\t\t\t\t\t<OccurrenceDate>1716451200000</OccurrenceDate>\n" +
                "\t\t\t\t\t<OccurrenceTime Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</OccurrenceTime>\n" +
                "\t\t\t\t\t<RecurrenceCode Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[1]]></Code>\n" +
                "\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t</RecurrenceCode>\n" +
                "\t\t\t\t\t<Discrete>true</Discrete>\n" +
                // ДОБАВЛЯЕМ ТЕКУЩЕЕ ЗНАЧЕНИЕ РАЗМЕРА ОЧЕРЕДИ, ВОЗВРАЩАЕМОЕ ФУНКЦИЕЙ УЗЛОМ ОБСЛУЖИВАНИЯ
                "\t\t\t\t\t<ValueCode><![CDATA[service_"+node.getId()+".queueSize()]]></ValueCode>\n" +
                "\t\t\t\t</Statistics>";

        appendXmlFragment(b, d.getElementsByTagName("AnalysisData").item(0), s);

        // ДОБАВЛЕНИЕ ОБЪЕКТА SERVICE (УЗЕЛ ОБСЛУЖИВАНИЯ)
        s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>"+node.getId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[service_"+node.getId()+"]]></Name>\n" +
                // РАЗМЕЩЕНИЕ НА ГРАФЕ СО СМЕЩЕНИЕМ
                "\t\t\t\t\t<X>"+node.getLocation().getX()+40+"</X><Y>"+node.getLocation().getY()+"</Y>\n" +
                "\t\t\t\t\t<Label><X>-10</X><Y>-15</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>true</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Service]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Service]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336243141]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[seizeFromOnePool]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[true]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceSets]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[{ \n" +
                "  { resourcePool }\n" +
                "}]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourcePool]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                //
                "\t\t\t\t\t\t\t\t<Code><![CDATA[resourcePool_"+node.getId()+"]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceQuantity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[seizePolicy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[queueCapacity]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                //
                "\t\t\t\t\t\t\t\t<Code><![CDATA["+node.getQueue()+"]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[maximumCapacity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[delayTime]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeUnitValue\">\n" +
                //
                "\t\t\t\t\t\t\t\t<Code><![CDATA["+node.getTob()+"]]></Code>\n" +
                "\t\t\t\t\t\t\t\t<Unit Class=\"TimeUnits\"><![CDATA[SECOND]]></Unit>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[sendResources]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[destinationType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[destinationNode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[destinationAttractor]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[movingGoHome]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entityLocationQueue]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entityLocationDelay]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[priority]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[taskMayPreemptOtherTasks]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[taskPreemptionPolicy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[terminatedTasksEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[suspendResumeEntities]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[wrapUpTaskPolicyType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[wrapUpTaskPriority]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[wrapUpTaskPreemptionPolicy]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[customizeResourceChoice]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceChoiceCondition]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceSelectionMode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceRating]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[resourceComparison]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableTimeout]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[timeout]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enablePreemption]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[restoreEntityLocationOnExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[forceStatisticsCollection]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[wrapUpUsageState]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExitTimeout]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExitPreempted]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onSeizeUnit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnterDelay]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onAtExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExit]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                // ПРИ КАЖДОМ ВЫХОДЕ ЗАЯВКИ ИЗ УЗЛА ДОБАВЛЯЕМ В СТАТИСТИКУ ТЕКУЩИЙ РАЗМЕР ОЧЕРЕДИ
                "\t\t\t\t\t\t\t\t<Code><![CDATA[queueSize_"+node.getId()+".update()]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onTaskTerminated]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onTaskSuspended]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onTaskResumed]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onRemove]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);
    }

    public void appendAlpStart(INode node, Document d, DocumentBuilder b) throws IOException, SAXException {

        String s = "<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>" + node.getId() + "</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[source_" + node.getId() + "]]></Name>\n" +
                "\t\t\t\t\t<X>" + node.getLocation().getX() + 40 + "</X><Y>" + (node.getLocation().getY() - 50) + "</Y>\n" +
                "\t\t\t\t\t<Label><X>-5</X><Y>-25</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Source]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Source]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242928]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[arrivalType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[rate]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeUnitValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[" + node.getLambda() + "]]></Code>\n" +
                "\t\t\t\t\t\t\t\t<Unit Class=\"RateUnits\"><![CDATA[PER_SECOND]]></Unit>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[interarrivalTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[firstArrivalMode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[firstArrivalTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[rateSchedule]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[modifyRate]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[rateExpression]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[arrivalSchedule]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[setAgentParametersFromDB]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[databaseTable]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[arrivalDate]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[multipleEntitiesPerArrival]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[entitiesPerArrival]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n";

        if (Integer.parseInt(node.getLimit()) == 0) {
            s += "\t\t\t\t\t\t<Parameter>\n" +
                    "\t\t\t\t\t\t\t<Name><![CDATA[limitArrivals]]></Name>\n" +
                    "\t\t\t\t\t\t</Parameter>\n" +
                    "\t\t\t\t\t\t<Parameter>\n" +
                    "\t\t\t\t\t\t\t<Name><![CDATA[maxArrivals]]></Name>\n" +
                    "\t\t\t\t\t\t</Parameter>\n";
        } else {
            s += "\t\t\t\t\t\t<Parameter>\n" +
                    "\t\t\t\t\t\t\t<Name><![CDATA[limitArrivals]]></Name>\n" +
                    "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                    "\t\t\t\t\t\t\t\t<Code><![CDATA[true]]></Code>\n" +
                    "\t\t\t\t\t\t\t</Value>\n" +
                    "\t\t\t\t\t\t</Parameter>\n" +
                    "\t\t\t\t\t\t<Parameter>\n" +
                    "\t\t\t\t\t\t\t<Name><![CDATA[maxArrivals]]></Name>\n" +
                    "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                    "\t\t\t\t\t\t\t\t<Code><![CDATA[" + node.getLimit() + "]]></Code>\n" +
                    "\t\t\t\t\t\t\t</Value>\n" +
                    "\t\t\t\t\t\t</Parameter>";
        }

        s += "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationType]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationX]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationY]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationZ]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLatitude]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLongitude]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationGeoPlaceName]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationXYZInNetwork]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationNetwork]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationLevel]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationNode]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[locationAttractor]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[speed]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[newEntity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[changeDimensions]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[length]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[width]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[height]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[enableCustomStartTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[startTime]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[addToCustomPopulation]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[population]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[pushProtocol]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[discardHangingEntities]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onBeforeArrival]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onAtExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onExit]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onDiscard]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);

        s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>" + UniqueIDGenerator.getNewId() + "</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[timeMeasureStart]]></Name>\n" +
                "\t\t\t\t\t<X>" + node.getLocation().getX() + 40 + "</X><Y>" + (node.getLocation().getY()) + "</Y>\n" +
                "\t\t\t\t\t<Label><X>-60</X><Y>-20</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureStart]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureStart]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336243204]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);

        s = "<Connector>\n" +
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[connectorSTART]]></Name>\n" +
                "\t\t\t\t\t<X>0</X><Y>0</Y>\n" +
                "\t\t\t\t\t<Label><X>10</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model"+graph.getId()+"]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[timeMeasureStart]]></ItemName>\n" +
                "\t\t\t\t\t</SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<SourceConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureStart]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[in]]></ItemName>\n" +
                "\t\t\t\t\t</SourceConnectableItemReference>\n" +
                "\t\t\t\t\t<TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model"+graph.getId()+"]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[source_"+node.getId()+"]]></ItemName>\n" +
                "\t\t\t\t\t</TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<TargetConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Source]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[out]]></ItemName>\n" +
                "\t\t\t\t\t</TargetConnectableItemReference>\n" +
                "\t\t\t\t\t<Points>\n" +
                "\t\t\t\t\t\t<Point><X>"+(node.getLocation().getX()+40-12)+"</X><Y>"+node.getLocation().getY()+5+"</Y></Point>\n" +
                "\t\t\t\t\t\t<Point><X>"+(node.getLocation().getX()+40-10)+"</X><Y>"+(node.getLocation().getY()-50)+"</Y></Point>\n" +
                "\t\t\t\t\t</Points>\n" +
                "\t\t\t\t</Connector>";

        appendXmlFragment(b, d.getElementsByTagName("Connectors").item(0), s);

    }

    public void appendAlpEnd(INode node, Document d, DocumentBuilder b) throws IOException, SAXException {
        /*  ВСТАВЛЯЕМ ОБЪЕКТ*/
        String s = "\t\t\t\t<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>" + node.getId() +"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[sink_"+node.getId()+"]]></Name>\n" +
                "\t\t\t\t\t<X>" + (node.getLocation().getX()+40) + "</X><Y>" + (node.getLocation().getY()+50) + "</Y>\n" +
                "\t\t\t\t\t<Label><X>-10</X><Y>-20</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Sink]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[Sink]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336242929]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[SimulationTime.update()]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[destroyEntity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);

        s = "<EmbeddedObject>\n" +
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[timeMeasureEnd]]></Name>\n" +
                "\t\t\t\t\t<X>"+(node.getLocation().getX()+40)+"</X><Y>"+(node.getLocation().getY())+"</Y>\n" +
                "\t\t\t\t\t<Label><X>-45</X><Y>-20</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<ActiveObjectClass>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureEnd]]></ClassName>\n" +
                "\t\t\t\t\t</ActiveObjectClass>\n" +
                "\t\t\t\t\t<GenericParameterSubstitute>\n" +
                "\t\t\t\t\t\t<GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureEnd]]></ClassName>\n" +
                "\t\t\t\t\t\t\t<ItemName><![CDATA[1412336243203]]></ItemName>\n" +
                "\t\t\t\t\t\t</GenericParameterSubstituteReference>\n" +
                "\t\t\t\t\t</GenericParameterSubstitute>\n" +
                "\t\t\t\t\t<Parameters>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[startObjects]]></Name>\n" +
                "\t\t\t\t\t\t\t<Value Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t\t\t<Code><![CDATA[{ timeMeasureStart }]]></Code>\n" +
                "\t\t\t\t\t\t\t</Value>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[datasetCapacity]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t\t<Parameter>\n" +
                "\t\t\t\t\t\t\t<Name><![CDATA[onEnter]]></Name>\n" +
                "\t\t\t\t\t\t</Parameter>\n" +
                "\t\t\t\t\t</Parameters>\n" +
                "\t\t\t\t\t<ReplicationFlag>false</ReplicationFlag>\n" +
                "\t\t\t\t\t<Replication Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[100]]></Code>\n" +
                "\t\t\t\t\t</Replication>\n" +
                "\t\t\t\t\t<CollectionType>ARRAY_LIST_BASED</CollectionType>\n" +
                "\t\t\t\t\t<InitialLocationType>AT_ANIMATION_POSITION</InitialLocationType>\n" +
                "\t\t\t\t\t<XCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</XCode>\n" +
                "\t\t\t\t\t<YCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</YCode>\n" +
                "\t\t\t\t\t<ZCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ZCode>\n" +
                "\t\t\t\t\t<ColumnCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</ColumnCode>\n" +
                "\t\t\t\t\t<RowCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</RowCode>\n" +
                "\t\t\t\t\t<LatitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LatitudeCode>\n" +
                "\t\t\t\t\t<LongitudeCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[0]]></Code>\n" +
                "\t\t\t\t\t</LongitudeCode>\n" +
                "\t\t\t\t\t<LocationNameCode Class=\"CodeValue\">\n" +
                "\t\t\t\t\t\t<Code><![CDATA[\"\"]]></Code>\n" +
                "\t\t\t\t\t</LocationNameCode>\n" +
                "\t\t\t\t\t<InitializationType>SPECIFIED_NUMBER</InitializationType>\n" +
                "\t\t\t\t\t<InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t\t<TableReference>\n" +
                "\t\t\t\t\t\t</TableReference>\n" +
                "\t\t\t\t\t</InitializationDatabaseTableQuery>\n" +
                "\t\t\t\t\t<InitializationDatabaseType>ONE_AGENT_PER_DATABASE_RECORD</InitializationDatabaseType>\n" +
                "\t\t\t\t\t<QuantityColumn>\n" +
                "\t\t\t\t\t</QuantityColumn>\n" +
                "\t\t\t\t</EmbeddedObject>\n";

        appendXmlFragment(b, d.getElementsByTagName("EmbeddedObjects").item(0), s);

        s = "<Connector>\n" +
                "\t\t\t\t\t<Id>"+UniqueIDGenerator.getNewId()+"</Id>\n" +
                "\t\t\t\t\t<Name><![CDATA[connectorEND]]></Name>\n" +
                "\t\t\t\t\t<X>810</X><Y>220</Y>\n" +
                "\t\t\t\t\t<Label><X>10</X><Y>0</Y></Label>\n" +
                "\t\t\t\t\t<PublicFlag>false</PublicFlag>\n" +
                "\t\t\t\t\t<PresentationFlag>true</PresentationFlag>\n" +
                "\t\t\t\t\t<ShowLabel>false</ShowLabel>\n" +
                "\t\t\t\t\t<SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model"+graph.getId()+"]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[timeMeasureEnd]]></ItemName>\n" +
                "\t\t\t\t\t</SourceEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<SourceConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[TimeMeasureEnd]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[out]]></ItemName>\n" +
                "\t\t\t\t\t</SourceConnectableItemReference>\n" +
                "\t\t\t\t\t<TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[model"+graph.getId()+"]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Main]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[sink_"+node.getId()+"]]></ItemName>\n" +
                "\t\t\t\t\t</TargetEmbeddedObjectReference>\n" +
                "\t\t\t\t\t<TargetConnectableItemReference>\n" +
                "\t\t\t\t\t\t<PackageName><![CDATA[com.anylogic.libraries.processmodeling]]></PackageName>\n" +
                "\t\t\t\t\t\t<ClassName><![CDATA[Sink]]></ClassName>\n" +
                "\t\t\t\t\t\t<ItemName><![CDATA[in]]></ItemName>\n" +
                "\t\t\t\t\t</TargetConnectableItemReference>\n" +
                "\t\t\t\t\t<Points>\n" +
                "\t\t\t\t\t\t<Point><X>"+(node.getLocation().getX()+40+10)+"</X><Y>"+node.getLocation().getY()+5+"</Y></Point>\n" +
                "\t\t\t\t\t\t<Point><X>"+(node.getLocation().getX()+40-20)+"</X><Y>"+(node.getLocation().getY()-50)+"</Y></Point>\n" +
                "\t\t\t\t\t</Points>\n" +
                "\t\t\t\t</Connector>";
        appendXmlFragment(b, d.getElementsByTagName("Connectors").item(0), s);
    }
    public static void appendXmlFragment(
            DocumentBuilder docBuilder, Node parent,
            String fragment) throws IOException, SAXException {
        Document doc = parent.getOwnerDocument();
        Node fragmentNode = docBuilder.parse(
                        new InputSource(new StringReader(fragment)))
                .getDocumentElement();
        fragmentNode = doc.importNode(fragmentNode, true);
        parent.appendChild(fragmentNode);
    }
    /*А ЗДЕСЬ БУДЕТ ФУНКЦИЯ ОПРЕДЕЛЕНИЯ КОННЕКТОВ*/
}
