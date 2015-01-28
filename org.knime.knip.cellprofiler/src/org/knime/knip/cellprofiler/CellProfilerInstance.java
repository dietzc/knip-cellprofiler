package org.knime.knip.cellprofiler;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.ImgPlus;

import org.apache.commons.io.FileUtils;
import org.cellprofiler.knimebridge.CellProfilerException;
import org.cellprofiler.knimebridge.IFeatureDescription;
import org.cellprofiler.knimebridge.IKnimeBridge;
import org.cellprofiler.knimebridge.KnimeBridgeFactory;
import org.cellprofiler.knimebridge.PipelineException;
import org.cellprofiler.knimebridge.ProtocolException;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.container.AbstractCellFactory;
import org.knime.core.data.container.CellFactory;
import org.knime.core.data.container.ColumnRearranger;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.util.Pair;
import org.knime.knip.base.data.img.ImgPlusValue;
import org.knime.knip.cellprofiler.data.CellProfilerCell;
import org.knime.knip.cellprofiler.data.CellProfilerContent;
import org.knime.knip.cellprofiler.data.CellProfilerSegmentation;
import org.zeromq.ZMQException;

/**
 * Starts and manages an instance of CellProfiler.
 * 
 * @author Patrick Winter, University of Konstanz
 */
@SuppressWarnings("deprecation")
public class CellProfilerInstance {
	
	private static final boolean START_PROCESS = false;

	private Process m_pythonProcess;

	private boolean closed = false;

	private IKnimeBridge m_knimeBridge = new KnimeBridgeFactory()
			.newKnimeBridge();

	/**
	 * Creates a CellProfiler instance in a separate Python process and connects
	 * to it via TCP.
	 * 
	 * @param pipelineFile
	 *            The pipeline file to execute
	 * @throws IOException
	 *             If something goes wrong
	 * @throws URISyntaxException
	 * @throws ProtocolException
	 * @throws ZMQException
	 * @throws PipelineException
	 */
	public CellProfilerInstance(String pipelineFile) throws IOException,
			ZMQException, ProtocolException, URISyntaxException,
			PipelineException {
		// Do some error checks on the configured module path
		String cellProfilerModule = CellProfilerPreferencePage.getPath();
		if (cellProfilerModule.isEmpty()) {
			throw new IOException("Path to CellProfiler module not set");
		}
		if (!new File(cellProfilerModule).exists()) {
			throw new IOException("CellProfiler module " + cellProfilerModule
					+ " does not exist");
		}
		if (new File(cellProfilerModule).isDirectory()) {
			throw new IOException("CellProfiler module path "
					+ cellProfilerModule + " is a directory");
		}
		// Get a free port for communication with CellProfiler
		int port = getFreePort();
		// Start CellProfiler
		if (START_PROCESS) {
			ProcessBuilder processBuilder = new ProcessBuilder(cellProfilerModule, "" + port);
			m_pythonProcess = processBuilder.start();
		}
		// Connect to CellProfiler via the given port
		m_knimeBridge.connect(new URI("tcp://localhost:" + 8080));
		m_knimeBridge.loadPipeline(FileUtils.readFileToString(new File(pipelineFile)));
	}

	/**
	 * @return The number of images expected by the pipeline.
	 */
	public String[] getInputParameters() {
		List<String> inputParameters = m_knimeBridge.getInputChannels();
		return inputParameters.toArray(new String[inputParameters.size()]);
	}

	/**
	 * @return Spec of the output produced by the pipeline.
	 */
	public DataTableSpec getOutputSpec(DataTableSpec inSpec,
			Pair<String, String>[] imageColumns) {
		return createColumnRearranger(inSpec, imageColumns).createSpec();
	}

	/**
	 * Executes the pipeline and returns the results.
	 * 
	 * @param exec
	 *            Execution context needed to create a new table.
	 * @param inputTable
	 *            The input table.
	 * @param imageColumns
	 *            The image columns used by the pipeline.
	 * @return Table containing the metrics calculated by the pipeline.
	 * @throws IOException
	 *             If something goes wrong.
	 * @throws ProtocolException
	 * @throws PipelineException
	 * @throws CellProfilerException
	 * @throws ZMQException
	 * @throws CanceledExecutionException
	 */
	public BufferedDataTable execute(ExecutionContext exec,
			BufferedDataTable inputTable, Pair<String, String>[] imageColumns)
			throws IOException, ZMQException, CellProfilerException,
			PipelineException, ProtocolException, CanceledExecutionException {
		ColumnRearranger colRearranger = createColumnRearranger(
				inputTable.getDataTableSpec(), imageColumns);
		return exec.createColumnRearrangeTable(inputTable, colRearranger, exec);
	}

	private CellProfilerContent createCellProfilerContent() {
		CellProfilerContent content = new CellProfilerContent();
		for (String segmentationName : m_knimeBridge.getObjectNames()) {
			CellProfilerSegmentation segmentation = new CellProfilerSegmentation();
			for (IFeatureDescription featureDescription : m_knimeBridge
					.getFeatures(segmentationName)) {
				if (featureDescription.getType().equals(Double.class)) {
					double[] values = m_knimeBridge
							.getDoubleMeasurements((IFeatureDescription) featureDescription);
					segmentation.addDoubleFeature(featureDescription.getName(),
							values);
				} else if (featureDescription.getType().equals(Float.class)) {
					float[] values = m_knimeBridge
							.getFloatMeasurements((IFeatureDescription) featureDescription);
					segmentation.addFloatFeature(featureDescription.getName(),
							values);
				} else if (featureDescription.getType().equals(Integer.class)) {
					int[] values = m_knimeBridge
							.getIntMeasurements((IFeatureDescription) featureDescription);
					segmentation.addIntegerFeature(
							featureDescription.getName(), values);
				} else if (featureDescription.getType().equals(String.class)) {
					String value = m_knimeBridge
							.getStringMeasurement((IFeatureDescription) featureDescription);
					segmentation.addStringFeature(featureDescription.getName(),
							value);
				}
			}
			content.addSegmentation(segmentationName, segmentation);
		}
		return content;
	}

	/**
	 * Shuts down the CellProfiler instance.
	 */
	public void close() {
		if (!closed) {
			closed = true;
			m_knimeBridge.disconnect();
			if (START_PROCESS) {
				// Give the process 5 seconds to shut down gracefully then kill it
				new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							Thread.sleep(5000);
						} catch (InterruptedException e) {
						}
						m_pythonProcess.destroy();
					}
				});
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void finalize() throws Throwable {
		close();
		super.finalize();
	}

	/**
	 * Finds a free TCP port.
	 * 
	 * @return A free TCP port.
	 * @throws IOException
	 *             If opening the server socket fails.
	 */
	private static int getFreePort() throws IOException {
		int port;
		try {
			// With the argument 0 the socket finds a free port
			ServerSocket socket = new ServerSocket(0);
			port = socket.getLocalPort();
			socket.close();
		} catch (IOException e) {
			throw new IOException("Could not get a free port", e);
		}
		return port;
	}

	private ColumnRearranger createColumnRearranger(final DataTableSpec inSpec,
			final Pair<String, String>[] imageColumns) {
		ColumnRearranger rearranger = new ColumnRearranger(inSpec);
		DataColumnSpec[] colSpecs = new DataColumnSpec[1];
		String columnName = DataTableSpec.getUniqueColumnName(inSpec,
				"CellProfiler measurements");
		colSpecs[0] = new DataColumnSpecCreator(columnName,
				CellProfilerCell.TYPE).createSpec();
		final int[] colIndexes = new int[imageColumns.length];
		for (int i = 0; i < imageColumns.length; i++) {
			colIndexes[i] = inSpec.findColumnIndex(imageColumns[i].getSecond());
		}
		CellFactory factory = new AbstractCellFactory(colSpecs) {
			@Override
			public DataCell[] getCells(final DataRow row) {
				try {
					return createCells(row, inSpec, imageColumns, colIndexes);
				} catch (ZMQException | ProtocolException
						| CellProfilerException | PipelineException e) {
					throw new RuntimeException(e.getMessage(), e);
				}
			}
		};
		// Append columns from the factory
		rearranger.append(factory);
		return rearranger;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private DataCell[] createCells(final DataRow row,
			final DataTableSpec inSpec,
			final Pair<String, String>[] imageColumns, final int[] colIndexes)
			throws ProtocolException, ZMQException, CellProfilerException,
			PipelineException {
		Map<String, ImgPlus<?>> images = new HashMap<String, ImgPlus<?>>();
		for (int i = 0; i < colIndexes.length; i++) {
			ImgPlusValue<?> value = (ImgPlusValue<?>) row
					.getCell(colIndexes[i]);
			images.put(imageColumns[i].getFirst(), new ImgPlus(value
					.getImgPlus().getImg()));
		}
		m_knimeBridge.run(images);
		CellProfilerCell cell = new CellProfilerCell(
				createCellProfilerContent());
		return new DataCell[] { cell };
	}

}
