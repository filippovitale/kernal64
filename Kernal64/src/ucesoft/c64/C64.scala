package ucesoft.c64

import cpu._
import peripheral._
import javax.swing._
import java.awt.event._
import java.awt._
import ucesoft.c64.expansion.ExpansionPort
import ucesoft.c64.formats._
import ucesoft.c64.peripheral.sid.SID
import ucesoft.c64.formats.ExpansionPortFactory
import ucesoft.c64.trace.TraceListener
import ucesoft.c64.trace.TraceDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import ucesoft.c64.peripheral.bus.IECBus
import java.io._
import javax.swing.filechooser.FileFilter
import ucesoft.c64.peripheral.drive.C1541Emu
import ucesoft.c64.peripheral.drive.DriveLedListener
import java.util.Properties
import ucesoft.c64.peripheral.controlport.JoystickSettingDialog
import ucesoft.c64.peripheral.controlport.Joysticks._
import ucesoft.c64.peripheral.drive.C1541
import ucesoft.c64.peripheral.drive.Drive
import ucesoft.c64.peripheral.cia.CIA
import ucesoft.c64.trace.InspectPanel
import ucesoft.c64.util.D64Canvas
import ucesoft.c64.util.T64Canvas
import ucesoft.c64.util.C64FileView
import ucesoft.c64.peripheral.c2n.Datassette
import ucesoft.c64.peripheral.c2n.DatassetteState
import java.awt.geom.Path2D
import ucesoft.c64.peripheral.c2n.DatassetteListener
import ucesoft.c64.util.AboutCanvas
import ucesoft.c64.peripheral.bus.BusSnoop
import ucesoft.c64.peripheral.printer.MPS803
import ucesoft.c64.peripheral.printer.MPS803GFXDriver
import ucesoft.c64.peripheral.printer.MPS803ROM
import ucesoft.c64.cpu.CPU6510.CPUJammedException
import ucesoft.c64.util.VolumeSettingsPanel
import ucesoft.c64.expansion.REU
import ucesoft.c64.trace.BreakType
import ucesoft.c64.peripheral.sid.SIDDevice
import ucesoft.c64.util.DriveSpeedSettingsPanel
import ucesoft.c64.peripheral.rs232._
import ucesoft.c64.util.RS232StatusPanel
import ucesoft.c64.peripheral.drive.LocalDrive
import ucesoft.c64.util.DropBoxAuth
import ucesoft.c64.peripheral.drive.DropboxDrive
import ucesoft.c64.expansion.SwiftLink

object C64 extends App {
  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
  val c64 = new C64
  c64.run
}

class C64 extends C64Component with ActionListener with DriveLedListener with TraceListener {
  val componentID = "Commodore 64"
  val componentType = C64ComponentType.INTERNAL
  
  private[this] val VERSION = "0.9.9O"
  private[this] val CONFIGURATION_FILENAME = "C64.config"
  private[this] val CONFIGURATION_LASTDISKDIR = "lastDiskDirectory"
  private[this] val CONFIGURATION_FRAME_XY = "frame.xy"  
  private[this] val CONFIGURATION_FRAME_DIM = "frame.dim"
  private[this] val clock = Clock.setSystemClock(Some(errorHandler _))(mainLoop _)
  private[this] val mem = new CPU6510Mems.MAIN_MEMORY
  private[this] var cpu = CPU6510.make(mem)  
  private[this] var cpuExact = cpu.isExact
  private[this] var vicChip : vic.VIC = _
  private[this] val sid : SIDDevice = if (System.getProperty("sid2") != null) new ucesoft.c64.peripheral.sid.SID2 else new ucesoft.c64.peripheral.sid.SID
  private[this] var display : vic.Display = _
  private[this] val nmiSwitcher = new NMISwitcher
  private[this] val irqSwitcher = new IRQSwitcher
  private[this] val keyb = new keyboard.Keyboard(keyboard.DefaultKeyboardMapper,nmiSwitcher.keyboardNMIAction _)	// key listener
  private[this] val displayFrame = {
    val f = new JFrame("Kernal64 emulator ver. " + VERSION)
    f.addWindowListener(new WindowAdapter {
      override def windowClosing(e:WindowEvent) {
        close
      }
    })
    f.setIconImage(new ImageIcon(getClass.getResource("/resources/commodore.png")).getImage)    
    f
  }
  // -------------- MENU ITEMS -----------------
  private[this] val maxSpeedItem = new JCheckBoxMenuItem("Maximum speed")
  private[this] val loadFileItem = new JMenuItem("Load file from attached disk ...")
  private[this] val tapeMenu = new JMenu("Tape control...")
  private[this] val detachCtrItem = new JMenuItem("Detach cartridge")
  private[this] val cartMenu = new JMenu("Cartridge")
  // -------------------------------------------
  private[this] val bus = new IECBus
  private[this] var baLowUntil = -1L
  private[this] var baLow = false
  private[this] var dma = false
  private[this] var cpuWaitUntil = -1L
  private[this] val expansionPort = ExpansionPort.getExpansionPort
  // -------------------- TRACE ----------------
  private[this] var traceDialog : TraceDialog = _
  private[this] var diskTraceDialog : TraceDialog = _
  private[this] var inspectDialog : JDialog = _
  private[this] var traceItem,traceDiskItem : JCheckBoxMenuItem = _
  private[this] val busSnooper = new BusSnoop(bus)
  private[this] var busSnooperActive = false
  // -------------------- DISK -----------------
  private[this] var isDiskActive = true
  private[this] var attachedDisk : Option[D64] = None
  private[this] val c1541 = new C1541Emu(bus,this)
  private[this] val c1541_real = new C1541(0x00,bus,this)
  private[this] var drive : Drive = c1541_real
  private[this] var device9Drive : Drive = _
  private[this] var device9DriveEnabled = false
  private[this] val driveLed = new DriveLed
  private[this] val diskProgressPanel = new DriveLoadProgressPanel
  private[this] val diskSpeedDialog : JDialog = DriveSpeedSettingsPanel.getDialog(displayFrame,drive)
  // -------------------- TAPE -----------------
  private[this] var datassette : Datassette = _
  // ----------------- RS-232 ------------------
  private[this] val rs232 = BridgeRS232
  private[this] var activeRs232 : Option[RS232] = None 
  private[this] val AVAILABLE_RS232 : Array[RS232] = Array(TelnetRS232,
                                                           TCPRS232,FileRS232,
                                                           SwiftLink.getSL(nmiSwitcher.expansionPortNMI,None),
                                                           SwiftLink.getSL(nmiSwitcher.expansionPortNMI _,Some(REU.getREU(REU.REU_1750,mem,setDMA _,irqSwitcher.expPortIRQ _,None))))
  private[this] val rs232StatusPanel = new RS232StatusPanel
  // -------------------- PRINTER --------------
  private[this] var printerEnabled = false
  private[this] var printerGraphicsDriver = new MPS803GFXDriver(new MPS803ROM)
  private[this] var printer = new MPS803(bus,printerGraphicsDriver)  
  private[this] var printerDialog = {
    val dialog = new JDialog(displayFrame,"Print preview")
    val sp = new JScrollPane(printerGraphicsDriver)
    sp.getViewport.setBackground(Color.BLACK)
    dialog.getContentPane.add("Center",sp)
    printerGraphicsDriver.checkSize
    val buttonPanel = new JPanel
    val exportPNGBUtton = new JButton("Export as PNG")
    buttonPanel.add(exportPNGBUtton)
    exportPNGBUtton.setActionCommand("PRINT_EXPORT_AS_PNG")
    exportPNGBUtton.addActionListener(this)
    val clearButton = new JButton("Clear")
    buttonPanel.add(clearButton)
    clearButton.setActionCommand("PRINT_CLEAR")
    clearButton.addActionListener(this)
    dialog.getContentPane.add("South",buttonPanel)
    dialog.pack
    dialog
  }
  // -------------- AUDIO ----------------------
  private[this] val volumeDialog : JDialog = VolumeSettingsPanel.getDialog(displayFrame,sid.getDriver)
  // -------------------------------------------
  private[this] val configuration = {
    val props = new Properties
    val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
    if (propsFile.exists) {
      try {
        props.load(new FileReader(propsFile))
      }
      catch {
        case io:IOException =>
      }
    }
    props
  }
  
  // ------------ Control Port -----------------------
  private[this] val gameControlPort = new controlport.GamePadControlPort(configuration)
  private[this] val keypadControlPort = controlport.ControlPort.keypadControlPort
  private[this] val controlPortA = new controlport.ControlPortBridge(keypadControlPort,"Control Port 1")  
  private[this] val controlPortB = new controlport.ControlPortBridge(gameControlPort,"Control port 2")
  // -------------- Light Pen -------------------------
  private[this] val LIGHT_PEN_NO_BUTTON = 0
  private[this] val LIGHT_PEN_BUTTON_UP = 1
  private[this] val LIGHT_PEN_BUTTON_LEFT = 2
  private[this] var lightPenButtonEmulation = LIGHT_PEN_NO_BUTTON
  // -------------- Mouse -----------------------------
  private[this] var mouseEnabled = true
  
  private[this] class LightPenButtonListener extends MouseAdapter with C64Component {
    val componentID = "Light pen"
    val componentType = C64ComponentType.INPUT_DEVICE 
    
    override def mousePressed(e:MouseEvent) {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case LIGHT_PEN_BUTTON_UP => controlPortB.emulateUp         
        case LIGHT_PEN_BUTTON_LEFT => controlPortB.emulateLeft
      }
    }
    override def mouseReleased(e:MouseEvent) {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case _ => controlPortB.releaseEmulated
      }
    }
    //override def mouseDragged(e:MouseEvent) = mouseClicked(e)
    
    def init {}
    def reset {}
  }
  
  private[this] class Mouse1351 extends MouseAdapter with C64Component {
    val componentID = "Mouse Commodore 1351"
    val componentType = C64ComponentType.INPUT_DEVICE
    private[this] var x,y = 0
    
    override def mouseMoved(e:MouseEvent) = if (mouseEnabled) {
      x = display.getMouseX & 0x7F
      y = 0x7F - display.getMouseY & 0x7F
      sid.write(0xD419,x << 1)
      sid.write(0xD41A,y << 1)
    }
    
    override def mouseDragged(e:MouseEvent) = if (mouseEnabled) {
      x = display.getMouseX & 0x7F
      y = 0x7F - display.getMouseY & 0x7F
      sid.write(0xD419,x << 1)
      sid.write(0xD41A,y << 1)
    }
    
    override def getProperties = {
      properties.setProperty("X",x.toString)
      properties.setProperty("Y",y.toString)
      properties
    }
    
    def init {}
    def reset {}
  }
  // -------------------------------------------------
  private class IRQSwitcher extends C64Component {
    val componentID = "IRQ Switcher (CIA,VIC)"
    val componentType = C64ComponentType.INTERNAL 
    
    private[this] var ciaIRQLow = false
    private[this] var vicIRQLow = false
    private[this] var expPortIRQLow = false
    
    private def handleIRQ = {
      //Log.debug(s"Handling IRQ ciaIRQ=${ciaIRQLow} vicIRQ=${vicIRQLow}")
      cpu.irqRequest(ciaIRQLow || vicIRQLow || expPortIRQLow)
    }
    
    final def ciaIRQ(low:Boolean) {     
      //Log.debug("CIA setting IRQ as " + low)
      ciaIRQLow = low
      handleIRQ
    }
    final def vicIRQ(low:Boolean) {  
      //Log.debug("VIC setting IRQ as " + low)
      vicIRQLow = low
      handleIRQ
    }
    
    final def expPortIRQ(low:Boolean) {
      expPortIRQLow = low
      handleIRQ
    }
    
    override def getProperties = {
      properties.setProperty("CIA1 IRQ",ciaIRQLow.toString)
      properties.setProperty("VIC IRQ",vicIRQLow.toString)
      properties.setProperty("Expansion port IRQ",expPortIRQLow.toString)
      properties
    }
    
    def init {}
    
    def reset {
      ciaIRQLow = false
      vicIRQLow = false
      expPortIRQLow = false
    }
  }

  class NMISwitcher extends C64Component {
    val componentID = "NMI Switcher (CIA2)"
    val componentType = C64ComponentType.INTERNAL

    private var keyboardNMILow = false
    private var cia2NMILow = false
    private var expPortNMILow = false

    def keyboardNMIAction(low: Boolean) {
      keyboardNMILow = low
      handleNMI
    }
    def cia2NMIAction(low: Boolean) {
      cia2NMILow = low
      handleNMI
    }
    def expansionPortNMI(low: Boolean) {
      expPortNMILow = low
      handleNMI
    }

    @inline private def handleNMI = cpu.nmiRequest(keyboardNMILow || cia2NMILow || expPortNMILow)

    override def getProperties = {
      properties.setProperty("CIA2 NMI", cia2NMILow.toString)
      properties.setProperty("Keyboard restore NMI", keyboardNMILow.toString)
      properties.setProperty("Expansion port NMI", expPortNMILow.toString)
      properties
    }

    def init {}

    def reset {
      keyboardNMILow = false
      cia2NMILow = false
      expPortNMILow = false
    }
  }
  
  // ------------------------------------ Drag and Drop ----------------------------
  private[this] val DNDHandler = new TransferHandler {
    override def canImport(support:TransferHandler.TransferSupport) : Boolean = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    override def importData(support: TransferHandler.TransferSupport) : Boolean = {
        if (!canImport(support)) {
            return false
        }
        
        val t = support.getTransferable();

        try {
          import scala.collection.JavaConversions._
          t.getTransferData(DataFlavor.javaFileListFlavor).asInstanceOf[java.util.List[File]].headOption match {
            case None =>
            case Some(f) =>
              val name = f.getName.toUpperCase
              if (name.endsWith(".D64") || name.endsWith(".TAP") || name.endsWith(".PRG") || name.endsWith(".CRT")) {
                handleDND(f)
                return true
              }
              return false
          }
        } 
        catch {
          case t:Throwable =>
            return false
        }

        true
    }
  }
  
  // ------------- TAPE STATE & PROGRESS BAR ------------------
  private class TapeState extends JComponent with DatassetteListener {
    private[this] var state = DatassetteState.STOPPED
    val progressBar = new JProgressBar
    
    setPreferredSize(new Dimension(10,10))
    progressBar.setPreferredSize(new Dimension(100,15))
    progressBar.setVisible(false)
    setVisible(false)
    
    override def paint(g:Graphics) {
      val size = getSize()
      val g2 = g.asInstanceOf[Graphics2D]
      import DatassetteState._
      state match {
        case PLAYING =>
          g2.setColor(Color.GREEN.darker)
          val path = new Path2D.Float
          path.moveTo(0,0)
          path.lineTo(size.width,size.height / 2)
          path.lineTo(0,size.height)
          path.closePath
          g2.fill(path)
        case STOPPED =>
          g2.setColor(Color.BLACK)
          g2.fillRect(0,0,size.width,size.height)
        case RECORDING =>
          g2.setColor(Color.RED)
          g2.fillOval(0,0,size.width,size.height)
      }
    }
    
    def datassetteStateChanged(newState:DatassetteState.Value) {
      setVisible(true)
      progressBar.setVisible(true)
      state = newState
      repaint()
    }
    def datassetteUpdatePosition(perc:Int) {
      progressBar.setValue(perc)
    }
  }
  
  // ------------- DRIVE LED & PROGRESS BAR -------------------
  private[this] var driveLedOn = false
  
  private class DriveLed extends JComponent with C64Component {
    val componentID = "Drive led"
    val componentType = C64ComponentType.INTERNAL
    
    setPreferredSize(new Dimension(15,15))
    override def paint(g:Graphics) {
      val size = getSize()
      val g2 = g.asInstanceOf[Graphics2D]
      g2.setColor(Color.BLACK)
      g2.drawRect(0,0,size.width - 1,size.height - 1)
      g2.setColor(if (driveLedOn) Color.RED else Color.DARK_GRAY)
      g2.fillRect(1,1,size.width - 1,size.height - 1)
    }   
    
    override def getProperties = {
      properties.setProperty("Led on",driveLedOn.toString)
      properties
    }
    
    def init {}
    def reset {}
  }
  
  private class DriveLoadProgressPanel extends JProgressBar {
    setPreferredSize(new Dimension(150,15))
    setStringPainted(true)
    setString("")
    setVisible(false)
    
    def beginLoading(msg:String) {
      setVisible(true)
      setValue(0)
      setString(msg)
    }
    
    def updateValue(perc:Int) {
      setValue(perc)
    }
    
    def endLoading {
      setVisible(false)
    }
  }
  
  override def isOn = driveLedOn
  
  override def turnOn {
    if (!driveLedOn) {
      driveLedOn = true
      driveLed.repaint()
    }
  }
  override def turnOff {
    if (driveLedOn) {
      driveLedOn = false
      driveLed.repaint()
    }    
  }
  
  override def beginLoadingOf(fileName:String,indeterminate:Boolean=false) {
    diskProgressPanel.setIndeterminate(indeterminate)
    diskProgressPanel.beginLoading(fileName)
  }
  override def updateLoading(perc:Int) {
    diskProgressPanel.updateValue(perc)
  }
  override def endLoading {
    diskProgressPanel.endLoading
  }
  override def beginSavingOf(fileName:String) {
    diskProgressPanel.beginLoading(fileName)
    diskProgressPanel.setIndeterminate(true)
  }
  override def endSaving {
    diskProgressPanel.endLoading
    diskProgressPanel.setIndeterminate(false)
  }
    
  def reset {
    baLowUntil = 0
    cpuWaitUntil = 0
    baLow = false
    dma = false
    clock.maximumSpeed = false
    maxSpeedItem.setSelected(false)
  }
  
  def init {
    val sw = new StringWriter
    Log.setOutput(new PrintWriter(sw))
    
    Log.info("Building the system ...")
    ExpansionPort.addConfigurationListener(mem)
    // -----------------------    
    add(clock)
    add(mem)
    add(cpu)
    add(keyb)
    add(controlPortA)
    add(controlPortB)
    add(bus)
    add(expansionPort)
    add(rs232)    
    rs232.setRS232Listener(rs232StatusPanel)
    // -----------------------
    val bankedMemory = new vic.BankedMemory(mem,mem.CHAR_ROM,mem.COLOR_RAM)    
    ExpansionPort.setMemoryForEmptyExpansionPort(bankedMemory)
    ExpansionPort.addConfigurationListener(bankedMemory)
    import cia._
    // control ports
    val cia1CP1 = new CIA1Connectors.PortAConnector(keyb,controlPortA)
    val cia1CP2 = new CIA1Connectors.PortBConnector(keyb,controlPortB,() => vicChip.triggerLightPen)
    add(cia1CP1)
    add(cia1CP2)
    add(irqSwitcher)    
    // CIAs
    val cia1 = new CIA("CIA1",
    				   0xDC00,
    				   cia1CP1,
    				   cia1CP2,
    				   irqSwitcher.ciaIRQ _)
    val cia2CP1 = new CIA2Connectors.PortAConnector(bankedMemory,bus,rs232)
    val cia2CP2 = new CIA2Connectors.PortBConnector(rs232)
    add(cia2CP1)
    add(cia2CP2)
    add(nmiSwitcher)    
    val cia2 = new CIA("CIA2",
    				   0xDD00,
    				   cia2CP1,
    				   cia2CP2,
    				   nmiSwitcher.cia2NMIAction _)
    rs232.setCIA(cia2)
    vicChip = new vic.VIC(bankedMemory,mem.COLOR_RAM,irqSwitcher.vicIRQ _,baLow _)        
    // mapping I/O chips in memory
    val io = mem.IO    
    io.addBridge(cia1)
    io.addBridge(cia2)
    io.addBridge(vicChip)
    io.addBridge(sid)    
    display = new vic.Display(vicChip.SCREEN_WIDTH,vicChip.SCREEN_HEIGHT,displayFrame.getTitle,displayFrame)
    add(display)
    display.setPreferredSize(new java.awt.Dimension(vicChip.VISIBLE_SCREEN_WIDTH,vicChip.VISIBLE_SCREEN_HEIGHT))
    vicChip.setDisplay(display)
    displayFrame.getContentPane.add("Center",display)
    displayFrame.addKeyListener(keyb)
    displayFrame.addKeyListener(keypadControlPort)
    display.addMouseListener(keypadControlPort)    
    val lightPen = new LightPenButtonListener
    add(lightPen)
    display.addMouseListener(lightPen)
    val mouse = new Mouse1351
    add(mouse)
    display.addMouseMotionListener(mouse)
    traceDialog = TraceDialog.getTraceDialog(displayFrame,mem,this,display,vicChip)
    diskTraceDialog = TraceDialog.getTraceDialog(displayFrame,c1541_real.getMem,c1541_real)
    // drive led
    add(driveLed)        
    if (configuration.getProperty(CONFIGURATION_FRAME_XY) != null) {
      val xy = configuration.getProperty(CONFIGURATION_FRAME_XY) split "," map { _.toInt }
      displayFrame.setLocation(xy(0),xy(1))
    }    
    configureJoystick
    // drive
    c1541_real.setIsRunningListener(diskRunning => isDiskActive = diskRunning)
    add(c1541)
    add(c1541_real)    
    Log.setOutput(traceDialog.logPanel.writer)
    // tape
    datassette = new Datassette(cia1.setFlagLow _)
    mem.setDatassette(datassette)
    add(datassette)
    // printer
    add(printer)
    
    // info panel
    val infoPanel = new JPanel(new BorderLayout)
    val dummyPanel = new JPanel
    val tapePanel = new TapeState
    datassette.setTapeListener(tapePanel)
    dummyPanel.add(rs232StatusPanel)
    dummyPanel.add(tapePanel)
    dummyPanel.add(tapePanel.progressBar)
    dummyPanel.add(diskProgressPanel)
    dummyPanel.add(driveLed)
    infoPanel.add("East",dummyPanel)
    displayFrame.getContentPane.add("South",infoPanel)
    displayFrame.setTransferHandler(DNDHandler)
    Log.info(sw.toString)
  }
  
  override def afterInitHook {
	inspectDialog = InspectPanel.getInspectDialog(displayFrame,this)
  }
  
  private def errorHandler(t:Throwable) {
    t match {
      case j:CPUJammedException =>
        JOptionPane.showConfirmDialog(displayFrame,
            "CPU jammed at " + Integer.toHexString(cpu.getCurrentInstructionPC) + ". Do you want to open debugger or reset ?",
            "CPU jammed",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.ERROR_MESSAGE) match {
          case JOptionPane.YES_OPTION =>
            traceDialog.forceTracing(true)
            trace(true,true)
          case _ => 
            reset(true)
        }
      case _ =>
        Log.info("Fatal error occurred: " + cpu + "-" + t)
        Log.info(CPU6510.disassemble(mem,cpu.getCurrentInstructionPC).toString)
        t.printStackTrace(Log.getOut)
        t.printStackTrace
        JOptionPane.showMessageDialog(displayFrame,t.toString + " [PC=" + Integer.toHexString(cpu.getCurrentInstructionPC) + "]", "Fatal error",JOptionPane.ERROR_MESSAGE)
        trace(true,true)
    }    
  }
  
  private def mainLoop(cycles:Long) {
    // VIC PHI1
    vicChip.clock(cycles)
    //DRIVES
    if (isDiskActive) drive.clock(cycles)
    if (device9DriveEnabled) device9Drive.clock(cycles)
    // bus snoop
    if (busSnooperActive) busSnooper.clock(cycles)
    // printer
    if (printerEnabled) printer.clock(cycles)
    // CPU PHI2
    if (cpuExact) {
//      if (baLow && cycles > baLowUntil) {
//        cpu.setBaLow(false)
//        expansionPort.setBaLow(false)
//        baLow = false
//      }
      cpu.fetchAndExecute
    }
    else {
//      if (baLow && cycles > baLowUntil) {
//        expansionPort.setBaLow(false)
//        baLow = false
//      }
      //val canExecCPU = cycles > cpuWaitUntil && cycles > baLowUntil && !dma
      val canExecCPU = cycles > cpuWaitUntil && !baLow && !dma
      if (canExecCPU) cpuWaitUntil = cycles + cpu.fetchAndExecute
    }
  }
  
  private def setDMA(dma:Boolean) {
    this.dma = dma
    if (cpuExact) cpu.setDMA(dma)
  }
  
  private def baLow(low:Boolean) {
    /*
    if (cycles > baLowUntil) {
      baLowUntil = cycles
      baLow = true
      if (cpuExact) cpu.setBaLow(true)
      expansionPort.setBaLow(true)
      //Log.fine(s"BA low until ${cycles}")
    }
    */
    baLow = low
    if (cpuExact) cpu.setBaLow(low)
    expansionPort.setBaLow(low)
  }
    
  // ---------------------------------- GUI HANDLING -------------------------------
    
  final def actionPerformed(e:ActionEvent) {
    e.getActionCommand match {
      case "TRACE" =>
      	val traceItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
      	trace(true,traceItem.isSelected)
      case "TRACE_DISK" =>
      	val traceItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
      	trace(false,traceItem.isSelected)
      case "INSPECT" =>
        val selected = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        inspectDialog.setVisible(selected)
      case "RESET" =>
        reset(true)
      case "ATTACH_CTR" => 
        attachCtr
      case "DETACH_CTR" =>
        detachCtr
      case "MAXSPEED" =>
        val maxSpeedItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
        clock.maximumSpeed = maxSpeedItem.isSelected
        clock.pause
        sid.setFullSpeed(maxSpeedItem.isSelected)
        clock.play
      case "ADJUSTRATIO" =>
        val dim = display.asInstanceOf[java.awt.Component].getSize
        dim.height = (dim.width / vicChip.SCREEN_ASPECT_RATIO).toInt
        display.setPreferredSize(dim) 
        displayFrame.pack
      case "AUTORUN_DISK" =>
        attachDisk(true)
      case "ATTACH_DISK" =>
        attachDisk(false)
      case "LOAD_FILE" =>
        loadFileFromAttachedFile(true)
      case "JOY" =>
        joySettings
      case "PASTE" =>
        paste
      case "SNAPSHOT" =>
        takeSnapshot
      case "LOADPRG" =>
        loadPrg
      case "SAVEPRG" =>
        savePrg
      case "ZOOM1" =>
        zoom(1)
      case "ZOOM2" =>
        zoom(1.5)
      case "ZOOM3" =>
        zoom(2)
      case "PAUSE" =>
        Clock.systemClock.pause
      case "PLAY" =>
        Clock.systemClock.play
      case "SWAP_JOY" =>
        swapJoysticks
      case "DISK_RO" =>
        drive.setReadOnly(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected)
      case "DISK_TRUE_EMU" =>
        val trueEmu = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        bus.reset
        drive.setActive(false)
        drive = if (trueEmu) c1541_real 
        else {
          isDiskActive = true
          c1541
        }
        drive.setActive(true)
      case "DISK_CAN_GO_SLEEP" =>
        val canGoSleep = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        drive.setCanSleep(canGoSleep)
      case "DISK_SPEED" =>
        diskSpeedDialog.setVisible(true)
      case "NO_PEN" =>
        lightPenButtonEmulation = LIGHT_PEN_NO_BUTTON
        keypadControlPort.setLightPenEmulation(false)
        vicChip.enableLightPen(false)
      case "PEN_UP" =>
        lightPenButtonEmulation = LIGHT_PEN_BUTTON_UP
        vicChip.enableLightPen(true)
        keypadControlPort.setLightPenEmulation(true)
      case "PEN_LEFT" =>
        lightPenButtonEmulation = LIGHT_PEN_BUTTON_LEFT
        vicChip.enableLightPen(true)
        keypadControlPort.setLightPenEmulation(true)
      case "MOUSE_ENABLED" =>
        mouseEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "EXIT" => close
      case "TAPE" =>
        loadFileFromTape
      case "TAPE_PLAY" =>
        datassette.pressPlay
      case "TAPE_STOP" =>
        datassette.pressStop
      case "TAPE_RECORD" =>
        datassette.pressRecordAndPlay
      case "TAPE_REWIND" =>
        datassette.pressRewind
      case "ATTACH_TAPE" =>
        attachTape 
      case "ABOUT" =>
        showAbout
      case "BUS_SNOOP" =>
        busSnooperActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "PRINTER_PREVIEW" =>
        showPrinterPreview
      case "PRINT_CLEAR" =>
        printerGraphicsDriver.clearPages
      case "PRINT_EXPORT_AS_PNG" =>
        printerSaveImage
      case "PRINTER_ENABLED" =>
        printerEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        printer.setActive(printerEnabled)
      case "VOLUME" =>
        volumeDialog.setVisible(true)
      case "CPU_CE" => changeCPU(true)
      case "CPU" => changeCPU(false)
      case "CRT_PRESS" => ExpansionPort.getExpansionPort.freezeButton
      case "NO_REU" =>
        ExpansionPort.getExpansionPort.eject
        ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      case "REU_128" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1700,mem,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_256" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1764,mem,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_512" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1750,mem,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_16M" => 
        val fc = new JFileChooser
  	    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
  	    fc.setFileView(new C64FileView)
  	    fc.setFileFilter(new FileFilter {
  	      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".REU")
  	      def getDescription = "REU files"
  	    })
  	    fc.showOpenDialog(displayFrame) match {
  	      case JFileChooser.APPROVE_OPTION => 
  	        try {
  	          val reu = REU.getREU(REU.REU_16M,mem,setDMA _,irqSwitcher.expPortIRQ _,Some(fc.getSelectedFile))	          
  	          ExpansionPort.setExpansionPort(reu)
  	        }
  	        catch {
  	          case t:Throwable =>
  	            JOptionPane.showMessageDialog(displayFrame,t.toString, "REU loading error",JOptionPane.ERROR_MESSAGE)
  	        }
  	      case _ =>
  	    }	   
      case "MAKE_DISK" =>
        makeDisk
      case "RS232" =>
        manageRS232
      case "DEVICE9_DISABLED" =>
        device9DriveEnabled = false
      case "LOCAL_DRIVE_ENABLED" =>        
        device9Drive = new LocalDrive(bus,9)
        device9DriveEnabled = true
        changeLocalDriveDir
      case "DROPBOX_DRIVE_ENABLED" =>
        checkDropboxDrive
    }
  }
  
  private def checkDropboxDrive {
    try {
      if (DropBoxAuth.isAccessCodeRequested(configuration)) {                 
        device9Drive = new DropboxDrive(bus,DropBoxAuth.getDbxClient(configuration),9)
        device9DriveEnabled = true
      }
      else {
        if (DropBoxAuth.requestAuthorization(configuration,displayFrame)) {          
          device9Drive = new DropboxDrive(bus,DropBoxAuth.getDbxClient(configuration),9)
          device9DriveEnabled = true
        }
      }
    }
    catch {
        case t:Throwable =>
          t.printStackTrace
          device9DriveEnabled = false
          JOptionPane.showMessageDialog(displayFrame,t.toString, "Dropbox init error",JOptionPane.ERROR_MESSAGE)
      }
  }
  
  private def changeLocalDriveDir {
    val fc = new JFileChooser
    fc.setCurrentDirectory(device9Drive.asInstanceOf[LocalDrive].getCurrentDir)
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    fc.setDialogTitle("Choose the current device 9 local directory")
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        device9Drive.asInstanceOf[LocalDrive].setCurrentDir(fc.getSelectedFile)
      case _ =>
    }
  }
  
  private def manageRS232 {
    val choices = Array.ofDim[Object](AVAILABLE_RS232.length + 1)
    choices(0) = "None"
    Array.copy(AVAILABLE_RS232,0,choices,1,AVAILABLE_RS232.length)
    val currentChoice = activeRs232 match {
      case None => choices(0)
      case Some(rs) => rs
    }
    val choice = JOptionPane.showInputDialog(displayFrame,"Choose an rs-232 implementation","RS-232",JOptionPane.QUESTION_MESSAGE,null,choices,currentChoice)
    if (choice == choices(0) && activeRs232.isDefined) {
      activeRs232.get.setEnabled(false)
      activeRs232 = None
    }
    else
    if (choice != null) {
      val rs = choice.asInstanceOf[RS232]
      val conf = JOptionPane.showInputDialog(displayFrame,s"<html><b>${rs.getDescription}</b><br>Type the configuration string:</html>", s"${rs.componentID}'s configuration",JOptionPane.QUESTION_MESSAGE)
      if (conf != null) {
        try {
          rs.setConfiguration(conf)
          activeRs232 foreach { _.setEnabled(false) }                   
          activeRs232 = Some(rs)
          rs232.setRS232(rs)
          rs232.setEnabled(true)
          rs232StatusPanel.setVisible(true)
        }
        catch {
          case t:Throwable =>
            JOptionPane.showMessageDialog(displayFrame,t.toString, "RS-232 configuration error",JOptionPane.ERROR_MESSAGE)
        }
      }
    }
  }
  
  private def makeDisk {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION => 
        try {
          val diskLabel = JOptionPane.showInputDialog(displayFrame,"Insert disk label", "New Disk label",JOptionPane.QUESTION_MESSAGE)
          if (diskLabel != null) {
            var emptyFile = fc.getSelectedFile.toString
            if (!emptyFile.toUpperCase.endsWith(".D64")) emptyFile += ".d64"
            val emptyD64 = new D64(emptyFile,true)
            emptyD64.format(s"N:${diskLabel.toUpperCase},00")
            emptyD64.close
          }
        }
        catch {
          case t:Throwable => 
            JOptionPane.showMessageDialog(displayFrame,t.toString, "Disk making error",JOptionPane.ERROR_MESSAGE)
        }
      case _ =>
    }
  }
  
  private def changeCPU(cycleExact:Boolean) {
    clock.pause
    val oldCpu = cpu
    cpu = CPU6510.make(mem,cycleExact)
    cpu.initComponent
    cpuExact = cycleExact
    change(oldCpu,cpu)
    drive.changeCPU(cycleExact)
    
    reset(true)
  }
  
  private def handleDND(file:File) {
    val name = file.getName.toUpperCase
    if (name.endsWith(".CRT")) loadCartridgeFile(file)
    else {
      reset(false)
      clock.schedule(new ClockEvent("Loading",clock.currentCycles + 2200000,(cycles) => {
        if (name.endsWith(".PRG")) loadPRGFile(file,true)
	    else    
	    if (name.endsWith(".D64")) attachDiskFile(file,true)
	    else
	    if (name.endsWith(".TAP")) attachTapeFile(file,true)
      })
      )
      clock.play
    }
  }
  
  private def loadPRGFile(file:File,autorun:Boolean) {    
    val in = new FileInputStream(file)
    val start = in.read + in.read * 256
    var m = start
    var b = in.read
    var size = 0
    while (b != -1) {
      mem.write(m, b)
      m += 1
      size += 1
      b = in.read
    }
    in.close
    val end = start + size
    mem.write(45, end % 256)
    mem.write(46, end / 256)
    mem.write(0xAE,end % 256)
    mem.write(0xAF,end / 256)
    Log.info(s"BASIC program loaded from ${start} to ${end} size=${size}")
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
      insertTextIntoKeyboardBuffer("RUN" + 13.toChar)
    }
  }
  
  private def loadCartridgeFile(file:File) {
    try {          
      val ep = ExpansionPortFactory.loadExpansionPort(file.toString,irqSwitcher.expPortIRQ _,nmiSwitcher.expansionPortNMI _,mem.getRAM)
      println(ep)
      if (ep.isFreezeButtonSupported) cartMenu.setVisible(true)
      ExpansionPort.setExpansionPort(ep)
      Log.info(s"Attached cartridge ${ExpansionPort.getExpansionPort.name}")
      reset(false)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)  
      detachCtrItem.setEnabled(true)
    }
    catch {
      case t:Throwable =>
        t.printStackTrace(traceDialog.logPanel.writer)
        JOptionPane.showMessageDialog(displayFrame,t.toString, "Cartridge loading error",JOptionPane.ERROR_MESSAGE)
    }
    finally {
      clock.play
    }
  }
  
  private def attachDiskFile(file:File,autorun:Boolean) {
    try {
      val disk = new D64(file.toString)
      attachedDisk match {
        case Some(oldDisk) => oldDisk.close
        case None =>
      }
      attachedDisk = Some(disk)
      drive.setDriveReader(disk)
      loadFileItem.setEnabled(true)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
      if (autorun) {
        insertTextIntoKeyboardBuffer("LOAD\"*\",8,1" + 13.toChar + "RUN" + 13.toChar)
      }
    }
    catch {
      case t:Throwable =>
        t.printStackTrace
        JOptionPane.showMessageDialog(displayFrame,t.toString, "Disk attaching error",JOptionPane.ERROR_MESSAGE)
    }
  }
  
  private def attachTapeFile(file:File,autorun:Boolean) {
    datassette.setTAP(Some(new TAP(file.toString)))
    tapeMenu.setEnabled(true)
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
        insertTextIntoKeyboardBuffer("LOAD" + 13.toChar + "RUN" + 13.toChar)
      }
  }
  // -------------------------------------------------
  
  private def printerSaveImage {
    val fc = new JFileChooser
    fc.showSaveDialog(printerDialog) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
      	printerGraphicsDriver.saveAsPNG(file)
      case _ =>
    }
  }
  
  private def showPrinterPreview {
    printerGraphicsDriver.checkSize
    printerDialog.setVisible(true)
  }
  
  private def showAbout {
    val about = new AboutCanvas(mem.CHAR_ROM,VERSION)
    JOptionPane.showMessageDialog(displayFrame,about,"About",JOptionPane.INFORMATION_MESSAGE,new ImageIcon(getClass.getResource("/resources/commodore_file.png")))    
  }
  
  private def zoom(f:Double) {
    val dim = new Dimension((vicChip.VISIBLE_SCREEN_WIDTH * f).toInt,(vicChip.VISIBLE_SCREEN_HEIGHT * f).toInt)
    display.setPreferredSize(dim) 
    displayFrame.invalidate
    displayFrame.pack
  }
  
  private def savePrg {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        configuration.setProperty(CONFIGURATION_LASTDISKDIR,fc.getSelectedFile.getParentFile.toString)
        val out = new FileOutputStream(fc.getSelectedFile)
        val start = mem.read(43) + mem.read(44) * 256
        val end = mem.read(45) + mem.read(46) * 256 - 1
	    out.write(mem.read(43))
	    out.write(mem.read(44))
        for (m <- start to end) out.write(mem.read(m))
        out.close
        Log.info(s"BASIC program saved from ${start} to ${end}")
      case _ =>
    }
  }
  
  private def loadPrg {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>                
        loadPRGFile(fc.getSelectedFile,false)
      case _ =>
    }
  }
  
  private def takeSnapshot {
    val fc = new JFileChooser
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
      	display.saveSnapshot(file)
      case _ =>
    }
  }
  
  private def paste {
    val clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
    val contents = clipboard.getContents(null)
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
      val str = contents.getTransferData(DataFlavor.stringFlavor).toString
      new Thread {
        override def run {
          val len = mem.read(649)
          var strpos = 0
          while (strpos < str.length) {
            val size = if (len < str.length - strpos) len else str.length - strpos            
            for(i <- 0 until size) {
              val c = str.charAt(strpos)
              mem.write(631 + i,if (c != '\n') c else 0x0D)
              strpos += 1
            }
            mem.write(198,size)
            while (mem.read(198) > 0) Thread.sleep(1)
          }
        }
      }.start
    }
  }
  
  private def trace(cpu:Boolean,on:Boolean) {
    if (cpu) {
      Log.setOutput(traceDialog.logPanel.writer)
      traceDialog.setVisible(on)
      traceItem.setSelected(on)
    }
    else {
      if (on) Log.setOutput(diskTraceDialog.logPanel.writer) 
      else Log.setOutput(traceDialog.logPanel.writer)
      diskTraceDialog.setVisible(on)
      traceDiskItem.setSelected(on)
    }
  }
  
  private def reset(play:Boolean=true) {
    traceDialog.forceTracing(false)
    diskTraceDialog.forceTracing(false)
    if (Thread.currentThread != Clock.systemClock) clock.pause
    //if (play) ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
    resetComponent
    if (play) clock.play
  } 
  
  private def detachCtr {
    if (ExpansionPort.getExpansionPort.isEmpty) JOptionPane.showMessageDialog(displayFrame,"No cartridge attached!", "Detach error",JOptionPane.ERROR_MESSAGE)
    else {
      ExpansionPort.getExpansionPort.eject
      ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      reset(true)
    }
    detachCtrItem.setEnabled(false)
    cartMenu.setVisible(false)
  }
  
  private def attachCtr {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".CRT")
      def getDescription = "CRT files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        loadCartridgeFile(fc.getSelectedFile)
      case _ =>
    }
  }
  
  private def attachDisk(autorun:Boolean) {
    val fc = new JFileChooser
    fc.setAccessory(new javax.swing.JScrollPane(new D64Canvas(fc,mem.CHAR_ROM)))
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".D64")
      def getDescription = "D64 files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachDiskFile(fc.getSelectedFile,autorun)
      case _ =>
    }
  }
  
  private def loadFileFromAttachedFile(relocate:Boolean) {
    attachedDisk match {
      case None =>
        JOptionPane.showMessageDialog(displayFrame,"No disk attached!", "Loading error",JOptionPane.ERROR_MESSAGE)
      case Some(d64) =>
        Option(JOptionPane.showInputDialog(displayFrame,"Load file","*")) match {
          case None =>
          case Some(fileName) =>
            try {
              d64.loadInMemory(mem,fileName,relocate)
            }
            catch {
              case t:Throwable =>
                JOptionPane.showMessageDialog(displayFrame, "Errore while loading from disk: " + t.getMessage,"Loading error",JOptionPane.ERROR_MESSAGE)
            }
        }
    }
  }    
  
  private def loadFileFromTape {
    val fc = new JFileChooser
    fc.setAccessory(new javax.swing.JScrollPane(new T64Canvas(fc,mem.CHAR_ROM)))
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".T64")
      def getDescription = "T64 files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        val tape = new T64(fc.getSelectedFile.toString)
        try {
          val values = tape.entries map { e => e.asInstanceOf[Object] }
          JOptionPane.showInputDialog(displayFrame,"Select file to open:","Open file in " + fc.getSelectedFile.getName,JOptionPane.INFORMATION_MESSAGE,null,values,values(0)) match {
            case null =>
            case entry:T64Entry =>
              tape.loadInMemory(mem,entry)
              configuration.setProperty(CONFIGURATION_LASTDISKDIR,fc.getSelectedFile.getParentFile.toString)
          }
        }
        finally {
          tape.close
        }
      case _ =>
    }
  }
  
  private def attachTape {
    val fc = new JFileChooser
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".TAP")
      def getDescription = "TAP files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachTapeFile(fc.getSelectedFile,false)
      case _ =>
    }
  }
  
  private def insertTextIntoKeyboardBuffer(txt:String) {
    for(i <- 0 until txt.length) {
      mem.write(631 + i,txt.charAt(i))
    }
    mem.write(198,txt.length)
  }
  
  private def setMenu {
    val menuBar = new JMenuBar
    val fileMenu = new JMenu("File")
    val editMenu = new JMenu("Edit")
    val traceMenu = new JMenu("Trace")
    val optionMenu = new JMenu("Settings")
    val helpMenu = new JMenu("Help")    
    cartMenu.setVisible(false)
    
    menuBar.add(fileMenu)
    menuBar.add(editMenu)
    menuBar.add(traceMenu)
    menuBar.add(optionMenu)         
    menuBar.add(cartMenu)
    menuBar.add(helpMenu)
    
    val tapeItem = new JMenuItem("Load file from tape ...")
    tapeItem.setActionCommand("TAPE")
    tapeItem.addActionListener(this)
    fileMenu.add(tapeItem)
    
    val attachTapeItem = new JMenuItem("Attach tape ...")
    attachTapeItem.setActionCommand("ATTACH_TAPE")
    attachTapeItem.addActionListener(this)
    fileMenu.add(attachTapeItem)
        
    tapeMenu.setEnabled(false)
    fileMenu.add(tapeMenu)
    
    val tapePlayItem = new JMenuItem("Cassette press play")
    tapePlayItem.setActionCommand("TAPE_PLAY")
    tapePlayItem.addActionListener(this)
    tapeMenu.add(tapePlayItem)
    
    val tapeStopItem = new JMenuItem("Cassette press stop")
    tapeStopItem.setActionCommand("TAPE_STOP")
    tapeStopItem.addActionListener(this)
    tapeMenu.add(tapeStopItem)
    
    val tapeRecordItem = new JMenuItem("Cassette press record & play")
    tapeRecordItem.setActionCommand("TAPE_RECORD")
    tapeRecordItem.addActionListener(this)
    tapeMenu.add(tapeRecordItem)
    
    val tapeRewindItem = new JMenuItem("Cassette press rewind")
    tapeRewindItem.setActionCommand("TAPE_REWIND")
    tapeRewindItem.addActionListener(this)
    tapeMenu.add(tapeRewindItem)
    
    fileMenu.addSeparator
    
    val makeDiskItem = new JMenuItem("Make empty disk ...")
    makeDiskItem.setActionCommand("MAKE_DISK")
    makeDiskItem.addActionListener(this)
    fileMenu.add(makeDiskItem)
    
    val autorunDiskItem = new JMenuItem("Autorun disk ...")
    autorunDiskItem.setActionCommand("AUTORUN_DISK")
    autorunDiskItem.addActionListener(this)
    fileMenu.add(autorunDiskItem)
    
    val attachDiskItem = new JMenuItem("Attach disk ...")
    attachDiskItem.setActionCommand("ATTACH_DISK")
    attachDiskItem.addActionListener(this)
    fileMenu.add(attachDiskItem)
        
    loadFileItem.setEnabled(false)
    loadFileItem.setActionCommand("LOAD_FILE")
    loadFileItem.addActionListener(this)
    fileMenu.add(loadFileItem)    
    fileMenu.addSeparator
    
    val loadPrgItem = new JMenuItem("Load PRG file from local disk ...")
    loadPrgItem.setActionCommand("LOADPRG")
    loadPrgItem.addActionListener(this)
    fileMenu.add(loadPrgItem)
    
    val savePrgItem = new JMenuItem("Save PRG file to local disk ...")
    savePrgItem.setActionCommand("SAVEPRG")
    savePrgItem.addActionListener(this)
    fileMenu.add(savePrgItem)
    
    val localDriveItem = new JMenu("Drive on device 9 ...")
    fileMenu.add(localDriveItem)
    val group0 = new ButtonGroup
    val noLocalDriveItem = new JRadioButtonMenuItem("Disabled")
    noLocalDriveItem.setSelected(true)
    noLocalDriveItem.setActionCommand("DEVICE9_DISABLED")
    noLocalDriveItem.addActionListener(this)
    group0.add(noLocalDriveItem)
    localDriveItem.add(noLocalDriveItem)
    val localDriveEnabled = new JRadioButtonMenuItem("Local drive ...")
    localDriveEnabled.setActionCommand("LOCAL_DRIVE_ENABLED")
    localDriveEnabled.addActionListener(this)
    group0.add(localDriveEnabled)
    localDriveItem.add(localDriveEnabled)
    val dropboxDriveEnabled = new JRadioButtonMenuItem("Dropbox drive ...")
    dropboxDriveEnabled.setActionCommand("DROPBOX_DRIVE_ENABLED")
    dropboxDriveEnabled.addActionListener(this)
    group0.add(dropboxDriveEnabled)
    localDriveItem.add(dropboxDriveEnabled)
    
    fileMenu.addSeparator
    
    val resetItem = new JMenuItem("Reset")
    resetItem.setActionCommand("RESET")
    resetItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F12,0))
    resetItem.addActionListener(this)
    fileMenu.add(resetItem)
    
    fileMenu.addSeparator
    
    val attachCtrItem = new JMenuItem("Attach cartridge ...")
    attachCtrItem.setActionCommand("ATTACH_CTR")
    attachCtrItem.addActionListener(this)
    fileMenu.add(attachCtrItem)
        
    detachCtrItem.setEnabled(false)
    detachCtrItem.setActionCommand("DETACH_CTR")
    detachCtrItem.addActionListener(this)
    fileMenu.add(detachCtrItem)
    
    fileMenu.addSeparator
    
    val exitItem = new JMenuItem("Exit")
    exitItem.setActionCommand("EXIT")
    exitItem.addActionListener(this)
    fileMenu.add(exitItem)
    
    // edit
        
    val pasteItem = new JMenuItem("Paste text")
    pasteItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.Event.CTRL_MASK))
    pasteItem.setActionCommand("PASTE")
    pasteItem.addActionListener(this)
    editMenu.add(pasteItem)
    
    // trace
    
    traceItem = new JCheckBoxMenuItem("Trace CPU")
    traceItem.setSelected(false)
    traceItem.setActionCommand("TRACE")
    traceItem.addActionListener(this)    
    traceMenu.add(traceItem)  
    
    traceDiskItem = new JCheckBoxMenuItem("Trace Disk CPU")
    traceDiskItem.setSelected(false)
    traceDiskItem.setActionCommand("TRACE_DISK")
    traceDiskItem.addActionListener(this)    
    traceMenu.add(traceDiskItem)
    
    val inspectItem = new JCheckBoxMenuItem("Inspect components ...")
    inspectItem.setSelected(false)
    inspectItem.setActionCommand("INSPECT")
    inspectItem.addActionListener(this)    
    traceMenu.add(inspectItem)
    
    // settings
    
    val volumeItem = new JMenuItem("Volume settings ...")
    volumeItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.Event.ALT_MASK))
    volumeItem.setActionCommand("VOLUME")
    volumeItem.addActionListener(this)
    optionMenu.add(volumeItem)
    
    optionMenu.addSeparator
        
    maxSpeedItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F8,0))
    maxSpeedItem.setSelected(clock.maximumSpeed)
    maxSpeedItem.setActionCommand("MAXSPEED")
    maxSpeedItem.addActionListener(this)    
    optionMenu.add(maxSpeedItem)
    
    optionMenu.addSeparator
    
    val adjustRatioItem = new JMenuItem("Adjust display ratio")
    adjustRatioItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A,java.awt.Event.CTRL_MASK))
    adjustRatioItem.setActionCommand("ADJUSTRATIO")
    adjustRatioItem.addActionListener(this)
    optionMenu.add(adjustRatioItem)
    
    val zoomItem = new JMenu("Zoom")
    optionMenu.add(zoomItem)
    val zoom1Item = new JMenuItem("Zoom x 1")
    zoom1Item.setActionCommand("ZOOM1")
    zoom1Item.addActionListener(this)
    zoomItem.add(zoom1Item)
    val zoom2Item = new JMenuItem("Zoom x 1.5")
    zoom2Item.setActionCommand("ZOOM2")
    zoom2Item.addActionListener(this)
    zoomItem.add(zoom2Item)
    val zoom4Item = new JMenuItem("Zoom x 2")
    zoom4Item.setActionCommand("ZOOM3")
    zoom4Item.addActionListener(this)
    zoomItem.add(zoom4Item)
    
    optionMenu.addSeparator
        
    val joyAItem = new JMenuItem("Joystick...")
    joyAItem.setActionCommand("JOY")
    joyAItem.addActionListener(this)
    optionMenu.add(joyAItem)
    
    val swapJoyAItem = new JMenuItem("Swap joysticks")
    swapJoyAItem.setActionCommand("SWAP_JOY")
    swapJoyAItem.addActionListener(this)
    optionMenu.add(swapJoyAItem)
    
    val lightPenMenu = new JMenu("Light pen")
    optionMenu.add(lightPenMenu)
    val group3 = new ButtonGroup
    val noPenItem = new JRadioButtonMenuItem("No light pen")
    noPenItem.setSelected(true)
    noPenItem.setActionCommand("NO_PEN")
    noPenItem.addActionListener(this)
    group3.add(noPenItem)
    lightPenMenu.add(noPenItem)
    val penUp = new JRadioButtonMenuItem("Light pen with button up")
    penUp.setActionCommand("PEN_UP")
    penUp.addActionListener(this)
    group3.add(penUp)
    lightPenMenu.add(penUp)
    val penLeft = new JRadioButtonMenuItem("Light pen with button left")
    penLeft.setActionCommand("PEN_LEFT")
    penLeft.addActionListener(this)
    group3.add(penLeft)
    lightPenMenu.add(penLeft)
    
    val mouseEnabledItem = new JCheckBoxMenuItem("Mouse 1351 enabled")
    mouseEnabledItem.setSelected(true)
    mouseEnabledItem.setActionCommand("MOUSE_ENABLED")
    mouseEnabledItem.addActionListener(this)    
    optionMenu.add(mouseEnabledItem)
    
    optionMenu.addSeparator
    
    val snapshotItem = new JMenuItem("Take a snapshot...")
    snapshotItem.setActionCommand("SNAPSHOT")
    snapshotItem.addActionListener(this)
    optionMenu.add(snapshotItem)
    
    optionMenu.addSeparator
    
    val group2 = new ButtonGroup
    val pauseItem = new JRadioButtonMenuItem("Pause")
    pauseItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F9,0))
    pauseItem.setActionCommand("PAUSE")
    pauseItem.addActionListener(this)
    optionMenu.add(pauseItem)
    group2.add(pauseItem)    
    val playItem = new JRadioButtonMenuItem("Play")
    playItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F11,0))
    playItem.setSelected(true)
    playItem.setActionCommand("PLAY")
    playItem.addActionListener(this)
    optionMenu.add(playItem)
    group2.add(playItem)
    
    optionMenu.addSeparator
    
    val printerPreviewItem = new JMenuItem("Printer preview ...")    
    printerPreviewItem.setActionCommand("PRINTER_PREVIEW")
    printerPreviewItem.addActionListener(this)
    optionMenu.add(printerPreviewItem)
    
    val printerEnabledItem = new JCheckBoxMenuItem("Printer enabled")
    printerEnabledItem.setSelected(false)
    printerEnabledItem.setActionCommand("PRINTER_ENABLED")
    printerEnabledItem.addActionListener(this)
    optionMenu.add(printerEnabledItem)
    
    optionMenu.addSeparator
    
    val diskItem = new JMenu("Drive")
    optionMenu.add(diskItem)
    
    val diskReadOnlyItem = new JCheckBoxMenuItem("Read only disk")
    diskReadOnlyItem.setSelected(false)
    diskReadOnlyItem.setActionCommand("DISK_RO")
    diskReadOnlyItem.addActionListener(this)    
    diskItem.add(diskReadOnlyItem)
    
    val diskTrueEmuItem = new JCheckBoxMenuItem("1541 True emulation")
    diskTrueEmuItem.setSelected(true)
    diskTrueEmuItem.setActionCommand("DISK_TRUE_EMU")
    diskTrueEmuItem.addActionListener(this)    
    diskItem.add(diskTrueEmuItem)
    
    val diskCanSleepItem = new JCheckBoxMenuItem("1541 can go sleeping")
    diskCanSleepItem.setSelected(true)
    diskCanSleepItem.setActionCommand("DISK_CAN_GO_SLEEP")
    diskCanSleepItem.addActionListener(this)    
    diskItem.add(diskCanSleepItem)
    
    val diskSpeedItem = new JMenuItem("1541 speed ...")
    diskSpeedItem.setActionCommand("DISK_SPEED")
    diskSpeedItem.addActionListener(this)    
    diskItem.add(diskSpeedItem)
    
    val busSnooperActiveItem = new JCheckBoxMenuItem("Bus snoop active")
    busSnooperActiveItem.setSelected(false)
    busSnooperActiveItem.setActionCommand("BUS_SNOOP")
    busSnooperActiveItem.addActionListener(this)    
    optionMenu.add(busSnooperActiveItem)
    
    optionMenu.addSeparator
    
    val rs232Item = new JMenuItem("RS-232 ...")
    rs232Item.setActionCommand("RS232")
    rs232Item.addActionListener(this)    
    optionMenu.add(rs232Item)
    
    optionMenu.addSeparator
    
    val reuItem = new JMenu("REU")
    val group5 = new ButtonGroup
    val noReuItem = new JRadioButtonMenuItem("None")
    noReuItem.setSelected(true)
    noReuItem.setActionCommand("NO_REU")
    noReuItem.addActionListener(this)
    group5.add(noReuItem)
    reuItem.add(noReuItem)
    val reu128Item = new JRadioButtonMenuItem("128K")
    reu128Item.setActionCommand("REU_128")
    reu128Item.addActionListener(this)
    group5.add(reu128Item)
    reuItem.add(reu128Item)
    val reu256Item = new JRadioButtonMenuItem("256K")
    reu256Item.setActionCommand("REU_256")
    reu256Item.addActionListener(this)
    group5.add(reu256Item)
    reuItem.add(reu256Item)
    val reu512Item = new JRadioButtonMenuItem("512K")
    reu512Item.setActionCommand("REU_512")
    reu512Item.addActionListener(this)
    group5.add(reu512Item)
    reuItem.add(reu512Item)
    val reu16MItem = new JRadioButtonMenuItem("16M ...")
    reu16MItem.setActionCommand("REU_16M")
    reu16MItem.addActionListener(this)
    group5.add(reu16MItem)
    reuItem.add(reu16MItem)
    
    optionMenu.add(reuItem)
    
    optionMenu.addSeparator
    
    val cpuItem = new JMenu("CPU")
    val group4 = new ButtonGroup
    val cpuExactItem = new JRadioButtonMenuItem("CPU exact")
    cpuExactItem.setSelected(cpuExact)
    cpuExactItem.setToolTipText("CPU with cycle exact behaviour")
    cpuExactItem.setActionCommand("CPU_CE")
    cpuExactItem.addActionListener(this)
    cpuItem.add(cpuExactItem)
    group4.add(cpuExactItem)
    val cpuFastItem = new JRadioButtonMenuItem("Faster CPU")    
    cpuFastItem.setToolTipText("Faster CPU with non-cycle exact behaviour")
    cpuFastItem.setActionCommand("CPU")
    cpuFastItem.addActionListener(this)
    cpuItem.add(cpuFastItem)
    optionMenu.add(cpuItem)
    group4.add(cpuFastItem)
    
    // cartridge
    val cartButtonItem = new JMenuItem("Press cartridge button...")
    cartButtonItem.setActionCommand("CRT_PRESS")
    cartButtonItem.addActionListener(this)
    cartMenu.add(cartButtonItem)
    
    // help
    
    val aboutItem = new JMenuItem("About")
    aboutItem.setActionCommand("ABOUT")
    aboutItem.addActionListener(this)
    helpMenu.add(aboutItem)        
    
    displayFrame.setJMenuBar(menuBar)
  }
  
  private def configureJoystick {
    def getControlPortFor(id:String) = configuration.getProperty(id) match {
      case CONFIGURATION_KEYPAD_VALUE => keypadControlPort
      case CONFIGURATION_JOYSTICK_VALUE => gameControlPort
      case _ => controlport.ControlPort.emptyControlPort
    }
    
    controlPortA.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_2)
    controlPortB.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_1)
  }
  
  private def joySettings {
    Clock.systemClock.pause
    try {
      // TODO
      val dialog = new JoystickSettingDialog(displayFrame,configuration)
      dialog.setVisible(true)
    }
    finally {
      Clock.systemClock.play
    }
  }
  
  private def swapJoysticks {
    val j1 = configuration.getProperty(CONFIGURATION_JOY_PORT_1)
    val j2 = configuration.getProperty(CONFIGURATION_JOY_PORT_2)
    if (j2 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_1,j2)
    if (j1 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_2,j1)
    configureJoystick
  }
  
  private def close {
    configuration.setProperty(CONFIGURATION_FRAME_XY,displayFrame.getX + "," + displayFrame.getY)
    configuration.setProperty(CONFIGURATION_FRAME_DIM,displayFrame.getSize.width + "," + displayFrame.getSize.height)
    try {
      val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
      val out = new FileWriter(propsFile)
      configuration.store(out, "C64 configuration file")
      out.close
      attachedDisk match {
        case Some(d64) => d64.close
        case None =>
      }
    }
    catch {
      case io:IOException =>
    }
    sys.exit(0)
  }
  
  // ------------------------------- TRACE LISTENER ------------------------------------------
  def setTraceOnFile(out:PrintWriter,enabled:Boolean) = cpu.setTraceOnFile(out,enabled)
  def setTrace(traceOn:Boolean) = cpu.setTrace(traceOn)
  def step(updateRegisters: (String) => Unit) = cpu.step(updateRegisters)
  def setBreakAt(breakType:BreakType,callback:(String) => Unit) = cpu.setBreakAt(breakType,callback)
  def jmpTo(pc:Int) = cpu.jmpTo(pc)
  // -----------------------------------------------------------------------------------------
  
  def run {
    //build
    initComponent
    setMenu
    displayFrame.pack
    if (configuration.getProperty(CONFIGURATION_FRAME_DIM) != null) {
      val dim = configuration.getProperty(CONFIGURATION_FRAME_DIM) split "," map { _.toInt }
      displayFrame.setSize(dim(0),dim(1))
    }
    displayFrame.setVisible(true)
    clock.play
  }
}