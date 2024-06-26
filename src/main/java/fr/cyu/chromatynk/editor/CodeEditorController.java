package fr.cyu.chromatynk.editor;

import fr.cyu.chromatynk.Chromatynk;
import fr.cyu.chromatynk.ChromatynkException;
import fr.cyu.chromatynk.ast.Program;
import fr.cyu.chromatynk.eval.*;
import fr.cyu.chromatynk.parsing.ParsingException;
import fr.cyu.chromatynk.parsing.ParsingIterator;
import fr.cyu.chromatynk.parsing.StatementParser;
import fr.cyu.chromatynk.parsing.Token;
import fr.cyu.chromatynk.util.Range;
import fr.cyu.chromatynk.util.Tuple2;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The controller for the code editor.
 * This class is responsible for handling the interactions and functionality of the code editor UI.
 * It includes methods for syntax highlighting, auto-indentation, script execution, and file handling.
 */
public class CodeEditorController implements Initializable {
    // The main elements
    @FXML
    private CodeArea codeArea;
    @FXML
    private Canvas canvas;
	public Canvas getCanvas() { return canvas; }
    @FXML
    private Canvas cursorCanvas;
	public Canvas getCursorCanvas() { return cursorCanvas; }

    // Interaction buttons
    @FXML
    private Button runButton;
    @FXML
    private Button stopButton;
    @FXML
    private Button clearTextAreaButton;

    // Bottom bar
    @FXML
    private HBox bottomBar;
    @FXML
    private Label infoLabel;
    @FXML
    private Label statusLabel;

	// Execution menu
	@FXML
	private Menu executionMenu;
    // Step-by-step mode
    @FXML
    private CheckMenuItem stepByStepCheckbox;
    @FXML
    private HBox stepByStepControls;
    @FXML
    private Label stepLabel;
	// Speed
	@FXML
	private ToggleGroup radioSpeedGroup;

    // Tabs
    @FXML
    private TabPane tabPane;
    @FXML
    private Tab plusTab;

    private int tabCounter = 1;
    private Map<Tab, String> tabContentMap = new HashMap<>(); // Map to store content of each tab

	// Output area
	@FXML
	private TextArea outputArea;

    private final Stage primaryStage;
    private FileMenuController fileMenuController;
	private ImageMenuController imageMenuController;
    private Clock timeoutClock;
    private Clock secondaryClock;
    private StepByStepClock stepByStepClock = new StepByStepClock(false);
    private ExecutionTimer currentExecution;
    private Subscription highlighting;
    private ExecutorService executor;

	/**
     * Constructor for the CodeEditorController.
     *
     * @param primaryStage the primary stage of the application
     */
    @SuppressWarnings("exports")
    public CodeEditorController(Stage primaryStage) {
		this.primaryStage = primaryStage;
	}
	
	/**
     * Creates and returns a Clock instance based on the selected speed from the UI.
     *
     * @return a Clock instance with the period set based on the selected speed
     */
    private Clock getPeriodClock() {
        long period = switch (((RadioMenuItem)radioSpeedGroup.getSelectedToggle()).getId()) {
            case "speed512" -> 1000/512;
            case "speed256" -> 1000/256;
            case "speed128" -> 1000/128;
            case "speed64" -> 1000/64;
            case "speed32" -> 1000/32;
            case "speed16" -> 1000/16;
            case "speed8" -> 1000/8;
            case "speed4" -> 1000/4;
            case "speed2" -> 1000/2;
            case "speed1" -> 1000;
            default -> 0;
        };

        return new PeriodClock(period);
    }
	
	/**
     * Creates and returns a Clock instance that combines timeoutClock and secondaryClock.
     *
     * @return a combined Clock instance
     */
    private Clock getClock() {
        return new AndClock(timeoutClock, secondaryClock);
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        timeoutClock = TimeoutClock.fps(30); //TODO change
        secondaryClock = getPeriodClock();

        executor = Executors.newSingleThreadExecutor();

        //Line numbers
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));

        //Lexical highlighting
        highlighting = this.codeArea
                .multiPlainChanges()
                .successionEnds(Duration.ofMillis(125))
                .retainLatestUntilLater(executor)
                .supplyTask(() -> computeHighlightingAsync(executor))
                .awaitLatest(codeArea.multiPlainChanges())
                .subscribe(tr -> {
                    tr.ifSuccess(highlighting -> codeArea.setStyleSpans(0, highlighting));
                    tr.ifFailure(Throwable::printStackTrace);
                });

        //Auto indent in body
        final Pattern whiteSpace = Pattern.compile("^\\s+");
        codeArea.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                int caretPosition = codeArea.getCaretPosition();
                int currentParagraph = codeArea.getCurrentParagraph();
                Matcher m0 = whiteSpace.matcher(codeArea.getParagraph(currentParagraph - 1).getSegments().get(0));
                if (m0.find()) Platform.runLater(() -> codeArea.insertText(caretPosition, m0.group()));
            }
        });

		// Set background color of the canvas (without using the function to prevent setting the status text)
		GraphicsContext graphicsContext = canvas.getGraphicsContext2D();
		graphicsContext.setFill(Color.WHITE);
		graphicsContext.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Only show relevant UI when in step-by-step mode
        stepByStepControls.setVisible(stepByStepCheckbox.isSelected());
        stepByStepCheckbox.selectedProperty().addListener((observable, oldValue, newValue) -> {stepByStepControls.setVisible(newValue);});

        // Handle tab changes
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab != null && oldTab != plusTab) {
                // Save content of the previous tab
                tabContentMap.put(oldTab, codeArea.getText());
            }
            if (newTab != null && newTab != plusTab) {
                // Load content of the new tab
                codeArea.replaceText(tabContentMap.getOrDefault(newTab, ""));
            }
            if (newTab == plusTab) {
                // Create a new tab
                tabCounter++;
                Tab newTabInstance = new Tab("Onglet " + tabCounter);
                newTabInstance.setClosable(true);

                // Add the new tab before the plus tab
                int plusTabIndex = tabPane.getTabs().indexOf(plusTab);
                tabPane.getTabs().add(plusTabIndex, newTabInstance);

                // Initialize content for the new tab
                tabContentMap.put(newTabInstance, "");

                // Add event handler to remove tab content when the tab is closed
                newTabInstance.setOnClosed(event -> tabContentMap.remove(newTabInstance));

                // Select the new tab
                tabPane.getSelectionModel().select(newTabInstance);
            }
        });

        this.fileMenuController = new FileMenuController(primaryStage, codeArea);
		this.imageMenuController = new ImageMenuController(primaryStage, canvas);
    }

    public void shutdown() {
        highlighting.unsubscribe();
        executor.shutdown();
    }

	/**
     * Computes syntax highlighting for the code area.
     *
     * @param executor the executor to run the task on
     * @return a task that computes the style spans for syntax highlighting
     */
    public Task<StyleSpans<Collection<String>>> computeHighlightingAsync(Executor executor) {
        String text = codeArea.getText();
        Task<StyleSpans<Collection<String>>> task = new Task<>() {
            @Override
            protected StyleSpans<Collection<String>> call() throws ParsingException {
                return computeHighlighting(text);
            }
        };

        executor.execute(task);
        return task;
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) throws ParsingException {
        List<Token> tokens = Chromatynk.lexSource(text);
        StyleSpansBuilder<Collection<String>> lexSpansBuilder = new StyleSpansBuilder<>();

        int lastKeywordEnd = 0;

        for (Token token : tokens) {
            String cssClass = switch (token) {
                case Token.LiteralBool ignored -> "boolean";
                case Token.LiteralString ignored -> "string";
                case Token.LiteralInt ignored -> "num";
                case Token.LiteralFloat ignored -> "num";
                case Token.LiteralColor ignored -> "color";
                case Token.Identifier ignored -> "identifier";
                case Token.Keyword ignored -> "keyword";
                default -> "default";
            };

            Tuple2<Integer, Integer> range1d = token.range().toCursorRange(text);

            lexSpansBuilder.add(Collections.singleton("default"), Math.max(0, range1d.a() - lastKeywordEnd));
            lexSpansBuilder.add(Collections.singleton(cssClass), range1d.b() - range1d.a());
            lastKeywordEnd = range1d.b();
        }

        StyleSpans<Collection<String>> result = lexSpansBuilder.create();

        StyleSpansBuilder<Collection<String>> errorSpansBuilder = new StyleSpansBuilder<>();

        try {
            Program program = StatementParser.program().parse(new ParsingIterator<>(tokens)).value();
            Chromatynk.typecheckProgram(program);
        } catch (ChromatynkException e) {
            Tuple2<Integer, Integer> range1d = e.getRange().toCursorRange(text);
            errorSpansBuilder.add(Collections.singleton("default"), Math.max(0, range1d.a()));
            errorSpansBuilder.add(Collections.singleton("error"), range1d.b() - range1d.a());

            result = result.overlay(errorSpansBuilder.create(), CodeEditorController.this::mergeClasses);
        }

        return result;
    }

    private Collection<String> mergeClasses(Collection<String> a, Collection<String> b) {
        List<String> classes = new ArrayList<>(a.size()+b.size());
        classes.addAll(a);
        classes.addAll(b);
        return classes;
    }

	/**
     * Opens a code file using the FileMenuController.
     */
    public void openFile() {
        fileMenuController.openFile();
    }

	/**
     * Saves the current file using the FileMenuController.
     */
    public void saveFile() {
        fileMenuController.saveFile();
    }

	/**
     * Saves the current canvas image using the ImageMenuController.
     */
	public void saveImage() {
		imageMenuController.saveImage();
	}

	/**
     * Clears the code area.
     */
    public void clearTextArea() {
        codeArea.clear();
    }

	/**
	 * Wipes the canvas.
	 */
	public void clearCanvas() {
		GraphicsContext graphicsContext = canvas.getGraphicsContext2D();
		graphicsContext.setFill(Color.WHITE);
		graphicsContext.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        clearCursorCanvas();

		infoLabel.setText("INFO - Dessin effacé");
		statusLabel.setText("Le dessin a été manuellement effacé.");
	}

	/**
	 * Wipes the canvas used for the cursors.
	 */
    private void clearCursorCanvas() {
        GraphicsContext graphicsContext = cursorCanvas.getGraphicsContext2D();
        graphicsContext.clearRect(0, 0, cursorCanvas.getWidth(), cursorCanvas.getHeight());
    }

	/**
	 * Opens the window used to change the size of the canvas.
	 * @param event
	 */
	@FXML
	private void modifyCanvas(ActionEvent event) {
		try {
			// Prepare and load the FXML file
			FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/ChangeCanvasSizeWindow.fxml"));

			Stage stage = new Stage();
			fxmlLoader.setController(new ChangeCanvasSizeController(this, stage));
			stage.initModality(Modality.APPLICATION_MODAL);
			stage.setTitle("Changer la taille du canevas");
			stage.setScene(new Scene(fxmlLoader.load()));
			stage.setResizable(false);
			stage.setMinWidth(200);
			stage.setMinHeight(100);

			// Add icon
			Image icon = new Image(getClass().getResourceAsStream("/icon.png"));
			stage.getIcons().add(icon);

			stage.show();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Opens the About window.
	 */
	@FXML
	public void openAboutWindow() {
		try {
			// Prepare and load the FXML file
			FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/AboutWindow.fxml"));

			Stage stage = new Stage();
			fxmlLoader.setController(new AboutWindowController(stage));

			stage.initModality(Modality.APPLICATION_MODAL);
			stage.setTitle("À propos de Chromat'ynk");

			stage.setScene(new Scene(fxmlLoader.load()));
			stage.setResizable(false);

			// Add icon
			Image icon = new Image(getClass().getResourceAsStream("/icon.png"));
			stage.getIcons().add(icon);

			stage.show();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
     * Closes the application.
     */
    public void quit() {
        primaryStage.close();
    }

	/**
     * Handles post-execution UI updates.
     */
    private void postExecution() {
        stopButton.setDisable(true);

        for(int i = 0; i < codeArea.getParagraphs().size(); i++) {
            codeArea.setParagraphStyle(i, Collections.singleton("default"));
        }
    }

	/**
     * Handles errors that occur during script execution.
     *
     * @param source the source code being executed
     * @param throwable the exception that was thrown
     */
    private void onError(String source, Throwable throwable) {
        if(throwable instanceof ChromatynkException e) {
            outputArea.setText(e.getFullMessage(source));
        } else {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            outputArea.setText(sw.toString());
        }

        infoLabel.setText("ERREUR - Dessin échoué");
        statusLabel.setText("L'exécution a été arrêtée par une erreur.");

        postExecution();
    }

	/**
     * Handles successful script execution.
     */
    private void onSuccess() {
        infoLabel.setText("INFO - Dessin complété");
        statusLabel.setText("Les instructions de dessin ont pu être complétées.");
		
        postExecution();
    }

	/**
     * Handles progress updates during script execution.
     *
     * @param context the evaluation context
     */
    private void onProgress(EvalContext context) {
        stepLabel.setText("Instruction " + context.getNextAddress()+1);
        clearCursorCanvas();
        context.render(cursorCanvas.getGraphicsContext2D());

        if(context.hasNext()) {
            Range range = context.getNextRange();
            for(int i = 0; i < codeArea.getParagraphs().size(); i++) {
                codeArea.setParagraphStyle(i, Collections.singleton(i == range.from().row() ? "evaluating" : "default"));
            }
        }
    }

	/**
     * Runs the script currently in the code area.
     */
    public void runScript() {
        stopScript();

        stopButton.setDisable(false);

		// Empty output and canvas
		clearCanvas();
		outputArea.clear();
		// Mark execution as currently running
		infoLabel.setText("INFO - Dessin en cours");
		statusLabel.setText("Les instructions de dessin sont en cours d'exécution.");

        getClock().reset();

        try {
            EvalContext context = Chromatynk.compileSource(codeArea.getText(), canvas.getGraphicsContext2D());
            currentExecution = new ExecutionTimer(context, getClock(), this::onSuccess, e -> onError(codeArea.getText(), e), this::onProgress);
            currentExecution.start();
        } catch (Throwable t) {
            onError(codeArea.getText(), t);
        }
    }

	/**
     * Stops the currently running script early.
     */
    public void stopScript() {
        if(currentExecution != null) {
            currentExecution.stop();
            currentExecution = null;
        }

		// Mark execution as stopped early
		infoLabel.setText("WARN - Dessin arrêté");
		statusLabel.setText("Le dessin a été manuellement interrompu lors de son exécution.");

        postExecution();
    }

	/**
     * Refreshes the secondary clock based on the step-by-step mode.
     */
    public void refreshSecondaryClock() {
        secondaryClock = stepByStepCheckbox.isSelected() ? stepByStepClock : getPeriodClock();
        if(currentExecution != null) currentExecution.setClock(secondaryClock);
    }

	/**
     * Executes the next instruction in step-by-step mode.
     */
    public void nextInstruction() {
        stepByStepClock.resume();
    }
}
