package org.janelia.saalfeldlab.n5.dataview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.thisptr.jackson.jq.internal.misc.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.fife.hex.swing.HexEditor;
import org.janelia.saalfeldlab.n5.*;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import javax.swing.*;
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

public class N5DataView extends JFrame {

    private JTextField pathField;
    private DefaultTreeModel treeModel;
    private JTree tree;
    private JPanel viewerPanel;

    N5DataView() {
        initUI();
    }

    private void initUI() {

        pathField = new JTextField(40);
        pathField.setText("/Users/rokickik/dev/ome-zarr-image-analysis-nextflow/data/xy_8bit__nuclei_PLK1_control.ome.zarr");

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

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("");
        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);

        tree.getSelectionModel().addTreeSelectionListener(e -> {

            DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getLastSelectedPathComponent();
            if (node == null) return;
            viewerPanel.removeAll();
            viewerPanel.updateUI();

            TreeNode treeNode = (TreeNode)node.getUserObject();
            Supplier<InputStream> contentSupplier = treeNode.getContentSupplier();
            if (contentSupplier != null) {
                InputStream inputStream = treeNode.getContentSupplier().get();
                if (inputStream != null) {
                    HexEditor hexEditor = new HexEditor();
                    try {
                        hexEditor.open(inputStream);
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    viewerPanel.add(hexEditor);
                }
            }
        });

        JScrollPane treeScrollPane = new JScrollPane(tree);
        treeScrollPane.setMinimumSize(new Dimension(200,200));

        viewerPanel = new JPanel();
        viewerPanel.setLayout(new BorderLayout());
        viewerPanel.setMinimumSize(new Dimension(200,200));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, viewerPanel);
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
                .zarrMapN5Attributes(false)
                .zarrMergeAttributes(false);
        try (N5Reader reader = n5Factory.openReader(pathStr)) {
            TreeNode treeNode = new TreeNode(path.getFileName().toString(), reader);
            DefaultMutableTreeNode root = treeNode.create();
            addChildren(reader, root, "", "");
            addMetadata(reader, root, "");
            tree.setRootVisible(true);
            treeModel.setRoot(root);
            setTreeExpandedState(tree, true);
        }
    }

    private int addChildren(N5Reader reader, DefaultMutableTreeNode parentNode, String path, String indent) {

        System.out.println(indent+"addChildren "+path);

        List<String> childNames = Arrays.asList(reader.list(path));
        Collections.sort(childNames);

        for (String childName : childNames) {
            String childPath = path+"/"+childName;

            if (reader.datasetExists(childName)) {

                TreeNode treeNode = new TreeNode(childName, null);

                DefaultMutableTreeNode childNode = treeNode.create();
                parentNode.add(childNode);

                DatasetAttributes datasetAttributes = reader.getDatasetAttributes(childPath);

                System.out.println(indent + " - " + childPath + " (data set) " + datasetAttributes);

                if (datasetAttributes != null) {
                    addDataSetAttributeNodes(reader, childNode, datasetAttributes);

                    //int numChildren = addChildren(reader, childNode, childPath, context, indent + "  ");
                    addMetadata(reader, childNode, childPath);

                    String contextRelativePath = childPath.replaceFirst(childPath, "") + "/0";

                    // Convert context relative path to grid position
                    long[] gridPosition = ArrayUtils.toPrimitive(
                            Arrays.stream(contextRelativePath.split("/"))
                                    .filter(StringUtils::isNotBlank)
                                    .map(Long::parseLong)
                                    .toArray(Long[]::new));

                    treeNode.setContentSupplier(() -> {
                        DataBlock<?> dataBlock = reader.readBlock(
                                childPath, datasetAttributes, gridPosition);
                        String posStr = StringUtils.join(ArrayUtils.toObject(gridPosition), ",");
                        if (dataBlock == null) {
                            System.out.println("No content at path=" + childPath + " pos=" + posStr);
                        } else {
                            System.out.println("Show content at path=" + childPath + " pos=" + posStr);
                        }
                        return dataBlock == null ? null : new ByteBufferBackedInputStream(dataBlock.toByteBuffer());
                    });
                }
            }
            else {
                System.out.println(indent+" - "+childPath+" (group)");

                TreeNode treeNode = new TreeNode(childName, null);
                DefaultMutableTreeNode childNode = treeNode.create();
                parentNode.add(childNode);

                addChildren(reader, childNode, childPath, indent+"  ");
                addMetadata(reader, childNode, childPath);
            }
        }

        return childNames.size();
    }

    private void addDataSetAttributeNodes(N5Reader reader, DefaultMutableTreeNode parentNode,
                                          DatasetAttributes datasetAttributes) {

        {
            DataType dataType = datasetAttributes.getDataType();
            TreeNode treeNode = new TreeNode("Data type: " + dataType.toString(), null);
            DefaultMutableTreeNode childNode = treeNode.create();
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
            TreeNode treeNode = new TreeNode(compressionSb.toString(), null);
            DefaultMutableTreeNode childNode = treeNode.create();
            parentNode.add(childNode);
        }

        {
            long[] dimensions = datasetAttributes.getDimensions();
            String dimensionsStr = Arrays.stream(dimensions)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(" x "));
            TreeNode treeNode = new TreeNode("Dimensions: " + dimensionsStr, null);
            DefaultMutableTreeNode childNode = treeNode.create();
            parentNode.add(childNode);
        }

        {
            int[] blockSize = datasetAttributes.getBlockSize();
            String blockSizeStr = Arrays.stream(blockSize)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(" x "));
            TreeNode treeNode = new TreeNode("Block size: " + blockSizeStr, null);
            DefaultMutableTreeNode childNode = treeNode.create();
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

    private String getPrimitives(JsonArray jsonArray) {
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

        if (jsonElement.isJsonArray()) {

            JsonArray jsonArray = jsonElement.getAsJsonArray();
            String primitives = getPrimitives(jsonArray);

            if (primitives == null) {
                // There are some non-primitive children
                TreeNode treeNode = new TreeNode(key, jsonElement);
                DefaultMutableTreeNode childNode = treeNode.create();
                parentNode.add(childNode);
                int i = 0;
                for (JsonElement childElement : jsonArray.asList()) {
                    addMetadata(reader, childNode, key+"["+i+"]", childElement);
                    i++;
                }
            }
            else {
                String label = String.format("%s = %s", key, primitives);
                TreeNode treeNode = new TreeNode(label, jsonElement);
                DefaultMutableTreeNode childNode = treeNode.create();
                parentNode.add(childNode);
            }
        }
        else if (jsonElement.isJsonObject()) {
            JsonObject childObject = jsonElement.getAsJsonObject();
            TreeNode treeNode = new TreeNode(key, childObject);
            DefaultMutableTreeNode childNode = treeNode.create();
            parentNode.add(childNode);
            addMetadata(reader, childNode, childObject);
        }
        else if (jsonElement.isJsonPrimitive()) {
            JsonPrimitive jsonPrimitive = jsonElement.getAsJsonPrimitive();
            String nodeName = key == null ? jsonPrimitive.toString() : String.format("%s = %s", key, jsonPrimitive);
            TreeNode treeNode = new TreeNode(nodeName, jsonPrimitive);
            parentNode.add(treeNode.create());
        }
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

    private class TreeNode {

        private String label;
        private Object object;
        private Supplier<InputStream> contentSupplier;

        public TreeNode(String label, Object object) {
            this.label = label;
            this.object = object;
        }
        public TreeNode(String label, Object object, Supplier<InputStream> contentSupplier) {
            this.label = label;
            this.object = object;
            this.contentSupplier = contentSupplier;
        }

        public String getLabel() {
            return label;
        }

        public Object getObject() {
            return object;
        }

        public Supplier<InputStream> getContentSupplier() {
            return contentSupplier;
        }

        public void setContentSupplier(Supplier<InputStream> contentSupplier) {
            this.contentSupplier = contentSupplier;
        }

        @Override
        public String toString() {
            return label;
        }

        private DefaultMutableTreeNode create() {
            return new DefaultMutableTreeNode(this);
        }
    }


    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            N5DataView n5View = new N5DataView();
            n5View.setVisible(true);
        });
    }
}
