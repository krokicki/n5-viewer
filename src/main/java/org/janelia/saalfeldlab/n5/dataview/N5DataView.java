package org.janelia.saalfeldlab.n5.dataview;

import bdv.util.BdvFunctions;
import bdv.util.BdvHandlePanel;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import com.google.gson.*;
import net.imglib2.cache.img.CachedCellImg;
import net.thisptr.jackson.jq.internal.misc.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.fife.hex.swing.HexEditor;
import org.janelia.saalfeldlab.n5.*;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A viewer for inspecting details of N5-compatible containers including OME-Zarr, HDF5, and N5.
 *
 * Inspired by the "HDF View".
 *
 * @author Konrad Rokicki
 */
public class N5DataView extends JFrame {

    public static final boolean DEBUG = false;

    private String defaultPath = "/Users/rokickik/dev/ome-zarr-image-analysis-nextflow/data/xy_8bit__nuclei_PLK1_control.ome.zarr";

    private JTextField pathField;
    private DefaultTreeModel treeModel;
    private JTree tree;
    private JTabbedPane tabbedPane;

    N5DataView() {
        initUI();
    }

    private void initUI() {

        pathField = new JTextField(40);
        pathField.setText(defaultPath);

        JButton openButton = new JButton("Open");
        openButton.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = pathField.getText();
                if (StringUtils.isBlank(path)) return;
                open(path);
            }
        });

        setTitle("N5 Data View");
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.LINE_AXIS));
        inputPanel.add(pathField);
        inputPanel.add(openButton);

        Container contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        contentPane.add(inputPanel, BorderLayout.NORTH);

        // Start with an empty tree and hide the root node
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(null);
        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().addTreeSelectionListener(this::handleTreeNodeSelected);

        JScrollPane treeScrollPane = new JScrollPane(tree);
        treeScrollPane.setMinimumSize(new Dimension(200,200));

        tabbedPane = new JTabbedPane();
        tabbedPane.setMinimumSize(new Dimension(200,200));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, tabbedPane);
        splitPane.setResizeWeight(0.3);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);

        contentPane.add(splitPane, BorderLayout.CENTER);

        pack();

        // Maximize the window after packing
        setExtendedState(JFrame.MAXIMIZED_BOTH);
    }

    private void open(String pathStr) {
        Path path = Paths.get(pathStr);
        N5Factory n5Factory = new N5Factory()
                .gsonBuilder(new GsonBuilder().setPrettyPrinting())
                .zarrMapN5Attributes(false)
                .zarrMergeAttributes(false);
        try (N5Reader reader = n5Factory.openReader(pathStr)) {
            N5TreeNode n5TreeNode = new N5TreeNode(reader, "/", path.getFileName().toString());
            DefaultMutableTreeNode root = n5TreeNode.wrap();
            addChildren(reader, root, "", "");
            addMetadata(reader, root, "");
            tree.setRootVisible(true);
            treeModel.setRoot(root);
            setTreeExpandedState(tree, true);
        }
    }

    private int addChildren(N5Reader reader, DefaultMutableTreeNode parentNode, String path, String indent) {

        if (DEBUG) System.out.println(indent+"addChildren "+path);

        List<String> childNames = Arrays.asList(reader.list(path));
        Collections.sort(childNames);

        for (String childName : childNames) {
            String childPath = path+"/"+childName;

            if (reader.datasetExists(childName)) {
                DatasetAttributes datasetAttributes = reader.getDatasetAttributes(childPath);
                if (DEBUG) System.out.println(indent + " - " + childPath + " (data set) " + datasetAttributes);

                if (datasetAttributes != null) {
                    N5DataSetTreeNode n5TreeNode = new N5DataSetTreeNode(childPath, childName, reader, datasetAttributes) {
                        public InputStream getInputStream(long[] gridPosition) {
                            DataBlock<?> dataBlock = reader.readBlock(
                                    childPath, datasetAttributes, gridPosition);
                            String posStr = StringUtils.join(ArrayUtils.toObject(gridPosition), ",");
                            if (dataBlock == null) {
                                if (DEBUG) System.out.println("No content at path=" + childPath + " pos=" + posStr);
                            } else {
                                if (DEBUG) System.out.println("Show content at path=" + childPath + " pos=" + posStr);
                            }
                            return dataBlock == null ? null : new ByteBufferBackedInputStream(dataBlock.toByteBuffer());
                        }
                    };
                    DefaultMutableTreeNode childNode = n5TreeNode.wrap();
                    parentNode.add(childNode);
                    addDataSetAttributeNodes(reader, childPath, childNode, datasetAttributes);
                    addMetadata(reader, childNode, childPath);
                }
                else {
                    throw new IllegalStateException("Data set without attributes: "+childPath);
                }
            }
            else {
                if (DEBUG) System.out.println(indent+" - "+childPath+" (group)");

                N5TreeNode n5TreeNode = new N5TreeNode(reader, childPath, childName);
                DefaultMutableTreeNode childNode = n5TreeNode.wrap();
                parentNode.add(childNode);

                addChildren(reader, childNode, childPath, indent+"  ");
                addMetadata(reader, childNode, childPath);
            }
        }

        return childNames.size();
    }

    private void addDataSetAttributeNodes(N5Reader reader, String path, DefaultMutableTreeNode parentNode,
                                          DatasetAttributes datasetAttributes) {

        {
            DataType dataType = datasetAttributes.getDataType();
            N5TreeNode n5TreeNode = new N5TreeNode(reader, path+"#dataType","Data type: " + dataType.toString());
            DefaultMutableTreeNode childNode = n5TreeNode.wrap();
            parentNode.add(childNode);
        }

        {
            Compression compression = datasetAttributes.getCompression();
            StringBuilder compressionSb = new StringBuilder("Compression: ");
            if (reader instanceof GsonN5Reader) {
                GsonN5Reader gsonN5Reader = (GsonN5Reader) reader;
                JsonElement jsonElement = gsonN5Reader.getGson().toJsonTree(compression);
                compressionSb.append(jsonElement);
            }
            else {
                compressionSb.append(compression.getType());
            }
            N5TreeNode n5TreeNode = new N5TreeNode(reader, path+"#compression", compressionSb.toString());
            DefaultMutableTreeNode childNode = n5TreeNode.wrap();
            parentNode.add(childNode);
        }

        {
            long[] dimensions = datasetAttributes.getDimensions();
            String dimensionsStr = Arrays.stream(dimensions)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(" x "));
            N5TreeNode n5TreeNode = new N5TreeNode(reader,path+"#dimensions", "Dimensions: " + dimensionsStr);
            DefaultMutableTreeNode childNode = n5TreeNode.wrap();
            parentNode.add(childNode);
        }

        {
            int[] blockSize = datasetAttributes.getBlockSize();
            String blockSizeStr = Arrays.stream(blockSize)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(" x "));
            N5TreeNode n5TreeNode = new N5TreeNode(reader, path+"#blockSize", "Block size: " + blockSizeStr);
            DefaultMutableTreeNode childNode = n5TreeNode.wrap();
            parentNode.add(childNode);
        }
    }

    private void addMetadata(N5Reader reader, DefaultMutableTreeNode parentNode, String path) {
        if (reader instanceof GsonN5Reader) {
            GsonN5Reader gsonN5Reader = (GsonN5Reader) reader;
            JsonElement attributes = gsonN5Reader.getAttributes(path);
            if (attributes != null) {
                JsonObject attrObject = attributes.getAsJsonObject();
                addMetadata(reader, parentNode, attrObject);
            }
        }
    }

    private void addMetadata(N5Reader reader, DefaultMutableTreeNode parentNode, JsonObject attrObject) {

        List<String> childNames = Lists.newArrayList(attrObject.keySet());
        Collections.sort(childNames);

        for (String key : childNames) {
            JsonElement jsonElement = attrObject.get(key);
            addMetadata(reader, parentNode, key, jsonElement);
        }
    }

    private String getPrimitiveCSV(JsonArray jsonArray) {
        StringBuilder builder = new StringBuilder();
        for (JsonElement childElement : jsonArray.asList()) {
            if (childElement.isJsonPrimitive()) {
                JsonPrimitive jsonPrimitive = childElement.getAsJsonPrimitive();
                if (builder.length()>0) builder.append(",");
                builder.append(jsonPrimitive.toString());
            }
            else {
                return null;
            }
        }
        return builder.toString();
    }
    private void addMetadata(N5Reader reader, DefaultMutableTreeNode parentNode, String key, JsonElement jsonElement) {

        N5TreeNode parentUserObject = (N5TreeNode)parentNode.getUserObject();

        if (jsonElement.isJsonArray()) {
            JsonArray jsonArray = jsonElement.getAsJsonArray();
            String primitiveCSV = getPrimitiveCSV(jsonArray);

            if (primitiveCSV == null) {
                // There are some non-primitive children, so we need to show all the array members as nodes
                N5TreeNode n5TreeNode = new N5MetadataTreeNode(reader, parentUserObject.getPath(), key, jsonArray);
                DefaultMutableTreeNode childNode = n5TreeNode.wrap();
                parentNode.add(childNode);
                int i = 0;
                for (JsonElement childElement : jsonArray.asList()) {
                    addMetadata(reader, childNode, key+"["+i+"]", childElement);
                    i++;
                }
            }
            else {
                // Show all primitive members as a single node using CSV format
                String label = String.format("%s = %s", key, primitiveCSV);
                N5TreeNode n5TreeNode = new N5MetadataTreeNode(reader, parentUserObject.getPath(), label, jsonArray);
                DefaultMutableTreeNode childNode = n5TreeNode.wrap();
                parentNode.add(childNode);
            }
        }
        else if (jsonElement.isJsonObject()) {
            JsonObject childObject = jsonElement.getAsJsonObject();
            N5TreeNode n5TreeNode = new N5MetadataTreeNode(reader, parentUserObject.getPath(), key, childObject);
            DefaultMutableTreeNode childNode = n5TreeNode.wrap();
            parentNode.add(childNode);
            addMetadata(reader, childNode, childObject);
        }
        else if (jsonElement.isJsonPrimitive()) {
            JsonPrimitive jsonPrimitive = jsonElement.getAsJsonPrimitive();
            String nodeName = key == null ? jsonPrimitive.toString() : String.format("%s = %s", key, jsonPrimitive);
            N5TreeNode n5TreeNode = new N5MetadataTreeNode(reader, parentUserObject.getPath(), nodeName, jsonElement);
            parentNode.add(n5TreeNode.wrap());
        }
    }

    private void handleTreeNodeSelected(TreeSelectionEvent e) {

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null) return;

        // Remove all current tabs
        tabbedPane.removeAll();;

        // Repopulate with new tabs
        N5TreeNode n5TreeNode = (N5TreeNode) node.getUserObject();
        for (EditorTab editorTab : n5TreeNode.getEditorTabs()) {
            tabbedPane.addTab(editorTab.getTitle(), editorTab.getIcon(),
                    editorTab.getComponent(), editorTab.getTip());
        }

        tabbedPane.updateUI();
    }

    // From https://www.logicbig.com/tutorials/java-swing/jtree-expand-collapse-all-nodes.html
    public static void setTreeExpandedState(JTree tree, boolean expanded) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        setNodeExpandedState(tree, node, expanded);
    }

    public static void setNodeExpandedState(JTree tree, DefaultMutableTreeNode node, boolean expanded) {
        ArrayList<DefaultMutableTreeNode> list = Collections.list(node.children());
        for (DefaultMutableTreeNode treeNode : list) {
            setNodeExpandedState(tree, treeNode, expanded);
        }
        if (!expanded && node.isRoot()) {
            return;
        }
        TreePath path = new TreePath(node.getPath());
        if (expanded) {
            tree.expandPath(path);
        } else {
            tree.collapsePath(path);
        }
    }

    private class N5TreeNode {

        protected final String label;
        protected final String path;
        protected final N5Reader reader;


        public N5TreeNode(N5Reader reader, String path, String label) {
            this.reader = reader;
            this.path = path;
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public N5Reader getReader() {
            return reader;
        }

        public String getPath() {
            return path;
        }

        public List<EditorTab> getEditorTabs() {
            return Collections.emptyList();
        }

        @Override
        public String toString() {
            return label;
        }

        public DefaultMutableTreeNode wrap() {
            return new DefaultMutableTreeNode(this);
        }
    }

    private class N5DataSetTreeNode extends N5TreeNode {

        private Supplier<InputStream> contentSupplier;
        private DatasetAttributes datasetAttributes;

        public N5DataSetTreeNode(String path, String label, N5Reader reader, DatasetAttributes datasetAttributes) {
            super(reader, path ,label);
            this.datasetAttributes = datasetAttributes;
        }


        public InputStream getInputStream(long[] gridPosition) {
            return null;
        }


        public List<EditorTab> getEditorTabs() {

            List<EditorTab> viewers = new ArrayList<>();

            try {
                // Initialize grid positions
                int numDimensions = datasetAttributes.getNumDimensions();
                long[] initialGridPosition = new long[numDimensions];
                for (int i = 0; i < numDimensions; i++) {
                    initialGridPosition[i] = 0;
                }

                // Create hex editor
                JPanel hexEditorControllerPanel = new JPanel();
                hexEditorControllerPanel.setLayout(new BoxLayout(hexEditorControllerPanel, BoxLayout.LINE_AXIS));
                JPanel hexEditorPanel = new JPanel();
                hexEditorPanel.setLayout(new BorderLayout());
                hexEditorPanel.add(hexEditorControllerPanel, BorderLayout.NORTH);

                HexEditor hexEditor = new HexEditor();
                InputStream inputStream = getInputStream(initialGridPosition);
                if (inputStream != null) {
                    hexEditor.open(inputStream);
                }
                hexEditorPanel.add(hexEditor, BorderLayout.CENTER);

                // Create a spinner control for each dimension
                SpinnerNumberModel[] models = new SpinnerNumberModel[numDimensions];
                for (int i = 0; i < numDimensions; i++) {
                    long dimension = datasetAttributes.getDimensions()[i];
                    int blockSize = datasetAttributes.getBlockSize()[i];
                    long numBlocks = dimension / blockSize;
                    SpinnerNumberModel blockModel = new SpinnerNumberModel(0, 0, numBlocks-1, 1);
                    models[i] = blockModel;
                    JSpinner spinner = new JSpinner(blockModel);
                    spinner.addChangeListener(e -> {
                        long[] gridPosition = new long[numDimensions];
                        for (int j = 0; j < numDimensions; j++) {
                            Double value = (Double) models[j].getValue();
                            gridPosition[j] = value.longValue();
                        }
                        SwingUtilities.invokeLater(() -> {
                            try {
                                InputStream inputStream2 = getInputStream(gridPosition);
                                if (inputStream2 != null) {
                                    hexEditor.open(inputStream2);
                                    hexEditor.updateUI();
                                }
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                    });
                    hexEditorControllerPanel.add(spinner);
                }

                viewers.add(new EditorTab(
                        "Hex Editor",
                        "Show the content in hex",
                        hexEditorPanel));

                BdvOptions options = BdvOptions.options().frameTitle("N5 Viewer");
                BdvHandlePanel bdvHandle = new BdvHandlePanel(N5DataView.this, options);
                CachedCellImg<?, ?> ts = N5Utils.openVolatile(getReader(), getPath());
                // We use the addTo option so as to not trigger a new window being opened
                BdvStackSource<?> show = BdvFunctions.show(ts, getPath(), BdvOptions.options().addTo(bdvHandle));
                viewers.add(new EditorTab(
                        "BigDataViewer",
                        "Displays the data set in a BDV",
                        show.getBdvHandle().getViewerPanel()));

            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            return viewers;
        }
    }

    private class N5MetadataTreeNode extends N5TreeNode {

        private JsonElement jsonElement;

        public N5MetadataTreeNode(N5Reader reader, String path, String label, JsonElement jsonElement) {
            super(reader, path, label);
            this.jsonElement = jsonElement;
        }

        public List<EditorTab> getEditorTabs() {
            if (reader instanceof GsonN5Reader) {
                GsonN5Reader gsonN5Reader = (GsonN5Reader) reader;
                String jsonText = gsonN5Reader.getGson().toJson(jsonElement);
                JTextArea textArea = new JTextArea();
                textArea.setEditable(false);
                textArea.setText(jsonText);
                JScrollPane scrollPane = new JScrollPane(textArea);
                return Collections.singletonList(new EditorTab("JSON", "Displays the object as JSON", scrollPane));
            }

            return Collections.emptyList();
        }
    }

    private class EditorTab {

        private final String title;
        private final String tip;
        private final Icon icon;
        private final JComponent component;

        public EditorTab(String title, String tip, JComponent component) {
            this(title, tip, null, component);
        }
        public EditorTab(String title, String tip, Icon icon, JComponent component) {
            this.title = title;
            this.tip = tip;
            this.icon = icon;
            this.component = component;
        }

        public String getTitle() {
            return title;
        }

        public String getTip() {
            return tip;
        }

        public Icon getIcon() {
            return icon;
        }

        public JComponent getComponent() {
            return component;
        }
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            N5DataView n5View = new N5DataView();
            n5View.setVisible(true);
        });
    }
}
