package fliegl.com.fjcounterhdcontroller;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * Created by Johannes Dürr on 06.07.17.
 */



public class JCounterHDController {

    private static final int WRITE                   =2;
    private static final int READ                    =1;
    private static final int CMD_FILTER_TIME         =16;
    private static final int CMD_AXIS_MODE           =17;
    private static final int CMD_AXIS_CALIB          =18;
    private static final int CMD_AXIS_THRESH_TIME    =19;
    private static final int CMD_AXIS_BOUNDS         =20;
    private static final int CMD_BASIC_LOCALNAME     =21;
    private static final int CMD_BASIC_MAJOR         =22;
    private static final int CMD_BASIC_MINOR         =23;
    private static final int CMD_BASIC_TXPOWER       =24;
    private static final int CMD_BASIC_UUID          =25;
    private static final int CMD_EEPROM_TRANSPORT    =26;
    private static final int CMD_READ_AXIS_CONFIG    =27;
    private static final int CMD_EEPROM_SELF_TEST    =28;
    private static final int CMD_FSEC_SET_USER_ROLE  =29;
    private static final int CMD_FSEC_SET_NEW_PIN    =30;
    private static final int CMD_SET_CURRENT_TIME    =31;
    private static final int CMD_READ_CURRENT_TIME   =32;
    private static final int CMD_SET_RADIO_POWER     =33;
    private static final int CMD_READ_RADIO_POWER    =34;

    public enum kRadioPowerLevel{
        kRadioPowerLevel_Highest_04_dB,
        kRadioPowerLevel_Default_00_dB,
        kRadioPowerLevel_Low_neg_04_dB,
        kRadioPowerLevel_Lower_0_neg_08_dB,
        kRadioPowerLevel_Lower_1_neg_12_dB,
        kRadioPowerLevel_Lower_2_neg_16_dB,
        kRadioPowerLevel_Lowest_neg_20_dB
    }

    // the partnering activity
    AppCompatActivity activity;

    // Bluetooth LE ingrediants
    final static public UUID flieglCounterUUID = UUID.fromString("C93AAAA0-C497-4C95-8699-01B142AF0C24");
    final static public UUID flieglCounterServiceUUID = UUID.fromString("C93ABBB0-C497-4C95-8699-01B142AF0C24");
    private static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID flieglCounterCommandCharacteristicUUID = UUID.fromString("C93ABBD3-C497-4C95-8699-01B142AF0C24");
    private static final UUID flieglCounterBootCharacteristicUUID = UUID.fromString("C93AAAA1-C497-4C95-8699-01B142AF0C24");

    private static final String TAG = "CounterHDController: ";
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private BluetoothAdapter mBluetoothAdapter;
    private int REQUEST_ENABLE_BT = 1;
    private Handler mHandler;
    private static final long SCAN_PERIOD = 2000;
    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filterlist = new ArrayList<>();
    private List<BluetoothDevice> peripheralList = new ArrayList<>();
    private Timer characteristicRetrievalTimer = null;
    private List<BluetoothGattCharacteristic> characteristicList = new ArrayList<>();
    private List<BluetoothGattCharacteristic> characteristicDoneList = new ArrayList<>();
    private BluetoothGattCharacteristic commandCharacteristic = null;
    private BluetoothGattCharacteristic bootCharacteristic = null;


    // GATT client
    BluetoothGatt mGatt = null;
    private BluetoothGattCallback mGattCallback = null;

    // Listener pattern receiving var
    private CounterHDControllerListener listener;


    // Constructor
    public JCounterHDController(final AppCompatActivity activity)
    {
        this.activity = activity;
        this.listener = null;
        characteristicRetrievalTimer = new Timer();

        // Make sure, we have propper permissions to use bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android M Permission check 
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                    public void onDismiss(DialogInterface dialog) {
                        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
            mHandler = new Handler();

            if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(activity, "BLE Not Supported",
                        Toast.LENGTH_SHORT).show();
                activity.finish();
            }
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Make sure we have propper scan filter and scanner in place to only find counterhd peripherals
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(flieglCounterUUID.toString())).build();
        filterlist.add(filter);
        mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

    }

    public void setCounterHDControllerListener(CounterHDControllerListener listener) {
        this.listener = listener;
    }


    /**
     *
     * @param enable : Whether or not to enable scanning.
     */
    public void startScanning(Boolean enable)
    {
        if (enable) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mLEScanner.stopScan(mScanCallback);
                }
            }, SCAN_PERIOD);
            mLEScanner.startScan(filterlist, settings, mScanCallback);
        } else {
            mLEScanner.stopScan(mScanCallback);
        }
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            BluetoothDevice btDevice = result.getDevice();

            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if(uuids != null)
            {
                if(!uuids.isEmpty())
                {
                    for(ParcelUuid uid : uuids)
                    {
                        if (uid.getUuid().toString().equalsIgnoreCase(flieglCounterUUID.toString()))
                        {
                            if (peripheralList.size() > 0) {
                                for (BluetoothDevice d : peripheralList) {
                                    if (d.getAddress().equalsIgnoreCase(btDevice.getAddress())) {
                                        Log.i(TAG, "Found: DUPLICATE " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                                    } else {
                                        peripheralList.add(btDevice);
                                        Log.i(TAG, "Found: " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                                    }
                                }
                            }else{
                                peripheralList.add(btDevice);
                                Log.i(TAG, "Found: " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                            }
                        }
                    }
                }
            }
            // tell listeners
            if (listener != null){
                listener.cc_didFindPeripherals(peripheralList);
            }
        }
    };

    private void readCharacteristicList(BluetoothGatt gatt)
    {
        if(characteristicDoneList.size()>0){
            characteristicList.removeAll(characteristicDoneList);
        }

        if (characteristicList.size() > 0){
            BluetoothGattCharacteristic c = characteristicList.get(0);
            if (isCharacterisitcReadable(c))
            {
                gatt.readCharacteristic(c);
            }
        }else {
            characteristicDoneList.removeAll(characteristicDoneList);
            characteristicList.removeAll(characteristicList);
        }
    }

    // Connection handling etc.


    /**
     * Cancel all current connections.
     */
    public void disconnectPeripheral(){

        if (mGatt != null){
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        peripheralList.clear();

    }

    /**
     *
     * @param d the BTLE Peripheral a connection should be established.
     */
    public void connectPeripheral(BluetoothDevice d){

        mGattCallback = new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (listener != null){
                    listener.cc_didChangeConnectionState(gatt, status, newState);



                    switch (newState){
                        case 0:
                            // Disconnected
                            Log.i(TAG, "mGatCallback: Disconnected");
                            break;
                        case 1:
                            // Connecteing
                            Log.i(TAG, "mGatCallback: Connecting");
                            break;
                        case 2:
                            // Connected
                            Log.i(TAG, "mGatCallback: Connected");
                            gatt.discoverServices();
                            break;

                        case 3:
                            // Disconnecting
                            Log.i(TAG, "mGatCallback: Disconnecting");
                            break;

                        default:
                            break;

                    }
                }
            }

            @Override
            // New services discovered
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Log.i(TAG, "Discovered a new Service....");
                mGatt = gatt;
                for (BluetoothGattService s : gatt.getServices()
                     ) {
                    // only counter service...
                    if (s.getUuid().toString().equalsIgnoreCase(flieglCounterServiceUUID.toString())) {
                        // add to characteristic list if not allready in there...
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()
                                ) {
                            if (!characteristicList.contains(c)) {
                                characteristicList.add(c);
                                Log.i(TAG, String.format("Added characteristic to list: %s from Service: %s", c.getUuid().toString(), c.getService().getUuid().toString()));
                            }
                            // find and remember the command characteristic
                            if (c.getUuid().toString().equalsIgnoreCase(flieglCounterCommandCharacteristicUUID.toString())){
                                commandCharacteristic = c;
                            }
                            if (c.getUuid().toString().equalsIgnoreCase(flieglCounterBootCharacteristicUUID.toString())){
                                bootCharacteristic = c;
                            }
                        }
                        TimerTask charTimeTask = new TimerTask() {
                            @Override
                            public void run() {
                            if (mGatt != null)
                                readCharacteristicList(mGatt);
                            }
                        };
                        characteristicRetrievalTimer.schedule(charTimeTask, 0, 500);
                    }
                }

            }

            @Override
            // Result of a characteristic read operation
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // check if notifiable
                    if (isCharacterisiticNotifiable(characteristic)){
                        // set notify
                        gatt.setCharacteristicNotification(characteristic, true);
                        // and write descriptor
                        BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(desc);
                        Log.i(TAG, String.format("Enabling notification for: %s", characteristic.getUuid().toString()));
                    }

                    for (BluetoothGattCharacteristic tChar : characteristicList
                         ) {
                        if (tChar.getUuid().toString().equalsIgnoreCase(characteristic.getUuid().toString())){
                            characteristicDoneList.add(tChar);
                        }
                    }

                    if (listener != null){
                        listener.cc_didReadCharacteristicValue(gatt, characteristic);
                    }
                    // process value
                    processCharacteristicValue(gatt, characteristic);
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                Log.i(TAG, String.format("Characteristic changed: %s", characteristic.getUuid().toString()));
                if (listener != null)
                {
                    listener.cc_didChangeCharacteristicValue(gatt, characteristic);
                }
                // process value
                processCharacteristicValue(gatt, characteristic);
            }
        };

        d.connectGatt(activity, false, mGattCallback);
    }

    // Updated or read characteristic values are processed here depending on wich UUID they state to have:
    private void processCharacteristicValue(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic){

        String uuidString = characteristic.getUuid().toString().toUpperCase();
        switch (uuidString){



            // Beacon Basic Info
            case "C93ABBB1-C497-4C95-8699-01B142AF0C24":
                Integer major;
                Integer minor;
                Integer txPower;
                String name = "Unknown Name";
                // minor
                minor = getIntFromBytes(characteristic.getValue(), 0,2);
                // major
                major = getIntFromBytes(characteristic.getValue(), 2, 2);
                // tx power
                txPower = getIntFromBytes(characteristic.getValue(), 15, 1);
                // local name
                try {
                    name = new String(characteristic.getValue(), 4,11, "UTF8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                if (listener != null) {
                    listener.cc_didUpdateBluetoothLocalName(name);
                    listener.cc_didUpdateMajorValue(major);
                    listener.cc_didUpdateMinorValue(minor);
                    listener.cc_didUpdateTXPower(txPower);
                }
                break;

            // UUID
            case "C93ABBB3-C497-4C95-8699-01B142AF0C24":

                String beaconUUID = "UUID unavailable";
                UUID cUUID = UUID.nameUUIDFromBytes(Arrays.copyOfRange(characteristic.getValue(), 0, 16));
                beaconUUID = cUUID.toString();
                if (listener != null){
                    listener.cc_didUpdateUUID(beaconUUID);
                }
                break;

            // Battery information
            case "C93ABBC0-C497-4C95-8699-01B142AF0C24":
                Integer charge = 0;
                Boolean isDCDCEnabled = false;
                charge = getIntFromBytes(characteristic.getValue(), 0, 1);
                if (characteristic.getValue()[1] == 0x01)
                {
                    isDCDCEnabled = true;
                }
                if (listener != null){
                    listener.cc_didUpdateBatteryInfo(charge, isDCDCEnabled);
                }
                break;

            // button state
            case "C93ABBB7-C497-4C95-8699-01B142AF0C24":
                byte testByte = (byte) characteristic.getValue()[0];
                if (testByte != 0){
                    Log.i(TAG, String.format("TESTBYTE: %d", testByte));
                }
                if(listener != null) {
                    if ((testByte & 1) == 1) {
                        // Button 3
                        listener.cc_didUpdateButton_3_state(true);
                    }else{
                        listener.cc_didUpdateButton_3_state(false);
                    }
                    if ((testByte & 2) == 2) {
                        // Button 1
                        listener.cc_didUpdateButton_1_state(true);
                    }else{
                        listener.cc_didUpdateButton_1_state(false);
                    }
                    if ((testByte & 4) == 4) {
                        // Button 2
                        listener.cc_didUpdateButton_2_state(true);
                    }else{
                        listener.cc_didUpdateButton_2_state(false);
                    }
                    if ((testByte & 8) == 8) {
                        // Button 4
                        listener.cc_didUpdateButton_4_state(true);
                    }else{
                        listener.cc_didUpdateButton_4_state(false);
                    }
                }

                break;

            // Event Totals
            case "C93ABBC3-C497-4C95-8699-01B142AF0C24":

                Integer xEventCount = getIntFromBytes(characteristic.getValue(),0,2);
                Integer yEventCount = getIntFromBytes(characteristic.getValue(),2,2);
                Integer zEventCount = getIntFromBytes(characteristic.getValue(),4,2);
                Integer xActiveTime = getIntFromBytes(characteristic.getValue(),6,2);
                Integer yActiveTime = getIntFromBytes(characteristic.getValue(),8,2);
                Integer zActiveTime = getIntFromBytes(characteristic.getValue(),10,2);
                Integer xProcessCount = getIntFromBytes(characteristic.getValue(),12,2);
                Integer yProcessCount = getIntFromBytes(characteristic.getValue(),14,2);
                Integer zProcessCount = getIntFromBytes(characteristic.getValue(),16,2);
                if (listener!= null){
                    listener.cc_didUpdateEventTotals(xEventCount,xActiveTime, xProcessCount, yEventCount, yActiveTime, yProcessCount, zEventCount, zActiveTime, zProcessCount);
                }
                break;

            // Device State
            case "C93ABBFF-C497-4C95-8699-01B142AF0C24":

                Integer deviceType = getIntFromBytes(characteristic.getValue(),0,2);
                Integer deviceRevision = getIntFromBytes(characteristic.getValue(),2,2);
                Integer buildNumber = getIntFromBytes(characteristic.getValue(), 4, 2);
                Integer rs_count = getIntFromBytes(characteristic.getValue(), 6, 2);

                Integer firmwareMajor = getIntFromBytes(characteristic.getValue(), 8, 1);
                Integer firmwareMinor = getIntFromBytes(characteristic.getValue(), 9, 1);;

                Integer statusBits = getIntFromBytes(characteristic.getValue(), 10, 4);
                Integer rs_time = getIntFromBytes(characteristic.getValue(), 14, 4);
                Integer user_role = getIntFromBytes(characteristic.getValue(), 18, 1);

                if (listener != null)
                {
                    listener.cc_didUpdateDeviceType(deviceType);
                    listener.cc_didUpdateDeviceRevision(deviceRevision);
                    listener.cc_didUpdateBuildNumber(buildNumber);
                    listener.cc_didUpdateRS_Count(rs_count);
                    listener.cc_didUpdateRS_Time(rs_time);
                    listener.cc_didUpdateFirmwareVersion(firmwareMajor, firmwareMinor);
                    listener.cc_didUpdateUserRole(user_role);
                    listener.cc_didUpdateStatusFlags(statusBits);
                }
                break;

            // Lis3dh incl Characteristic
            case "C93ABBB8-C497-4C95-8699-01B142AF0C24":
                Integer xInclination = getSIntFromBytes(characteristic.getValue(), 0, 2);
                Integer yInclination = getSIntFromBytes(characteristic.getValue(), 2, 2);
                Integer zInclination = getSIntFromBytes(characteristic.getValue(), 4, 2);
                Integer xCorrectedInclination = getSIntFromBytes(characteristic.getValue(), 6, 2);
                Integer yCorrectedInclination = getSIntFromBytes(characteristic.getValue(), 8, 2);
                Integer zCorrectedInclination = getSIntFromBytes(characteristic.getValue(), 10, 2);
                Integer xAcceleration = getSIntFromBytes(characteristic.getValue(), 12, 1);
                Integer yAcceleration = getSIntFromBytes(characteristic.getValue(), 13, 1);
                Integer zAcceleration = getSIntFromBytes(characteristic.getValue(), 14, 1);
                Integer freqBin1 = getIntFromBytes(characteristic.getValue(), 16, 1);
                Integer freqBin2 = getIntFromBytes(characteristic.getValue(), 17, 1);
                Integer freqBin3 = getIntFromBytes(characteristic.getValue(), 18, 1);
                Integer freqBin4 = getIntFromBytes(characteristic.getValue(), 19, 1);
                if (listener != null)
                {
                    listener.cc_didUpdateAccelerometerValues(xInclination, xCorrectedInclination, xAcceleration,
                            yInclination, yCorrectedInclination, yAcceleration,
                            zInclination, zCorrectedInclination, zAcceleration,
                            freqBin1, freqBin2, freqBin3, freqBin4);
                }
                break;

            // Param Transport (reading back predefined parameters)
            case "C93ABBC9-C497-4C95-8699-01B142AF0C24":
                int commandNumber = getIntFromBytes(characteristic.getValue(), 0, 1);
                switch (commandNumber)
                {
                    case CMD_READ_AXIS_CONFIG:
                        int axis = getIntFromBytes(characteristic.getValue(), 2,1);
                        int mode = getIntFromBytes(characteristic.getValue(), 3,1);
                        int flavor = getIntFromBytes(characteristic.getValue(), 4,1);
                        int filterTime = getIntFromBytes(characteristic.getValue(), 5,1);
                        int isInverted = getIntFromBytes(characteristic.getValue(), 6,1);
                        int isRSDependent = getIntFromBytes(characteristic.getValue(), 7,1);
                        int  topBound = getSIntFromBytes(characteristic.getValue(), 8,2);
                        int  botBound = getSIntFromBytes(characteristic.getValue(), 10,2);
                        int  topInertia = getIntFromBytes(characteristic.getValue(), 12,2);
                        int  botInertia = getIntFromBytes(characteristic.getValue(), 14,2);
                        if (listener != null)
                        {
                            listener.cc_didUpdateAxisConfiguration(axis, mode, flavor, filterTime, isInverted, isRSDependent, topBound, botBound, topInertia, botInertia);
                        }
                        break;

                    case CMD_EEPROM_SELF_TEST:
                        int eepromErrorCount =   getIntFromBytes(characteristic.getValue(), 2,1);
                        byte[] testResult = Arrays.copyOfRange(characteristic.getValue(),3, 13);
                        if (listener!=null)
                        {
                            listener.cc_didUpdateEEPROMSelftestResult(eepromErrorCount, testResult);
                        }
                        break;

                    case CMD_READ_CURRENT_TIME:
                        int secsSince1970 = getIntFromBytes(characteristic.getValue(),2,4);
                        Date date = new Date(secsSince1970);
                        if (listener!=null){
                            listener.cc_didUpdatePeripheralTimeDate(date);
                        }
                        break;

                    case CMD_READ_RADIO_POWER:
                        int radioPower = getSIntFromBytes(characteristic.getValue(),2,1);
                        if (listener!=null){
                            listener.cc_didUpdateRadioPower(radioPower);
                        }
                        break;

                    case CMD_EEPROM_TRANSPORT:

                        //@TODO: add implementation for Transport protocoll
                        break;

                    default:
                        break;
                }
                break;

            default:
                break;
        }
    }

    /* Peripheral manipulation methods */

    /**
     *
     * @param time_s : The Low Pass Filter time in seconds.
     */
    public void setPeripheral_LowPassFilterTime_X_s(final int time_s){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_FILTER_TIME;
        cmdVal[2] = (byte) time_s;
        cmdVal[3] = (byte) 0xff;
        cmdVal[4] = (byte) 0xff;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param time_s : The Low Pass Filter time in seconds.
     */
    public void setPeripheral_LowPassFilterTime_Y_s(final int time_s){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_FILTER_TIME;
        cmdVal[2] = (byte) 0xff;
        cmdVal[3] = (byte) time_s;
        cmdVal[4] = (byte) 0xff;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_LowPassFilterTime_Z_s(final int time_s){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_FILTER_TIME;
        cmdVal[2] = (byte) 0xff;
        cmdVal[3] = (byte) 0xff;
        cmdVal[4] = (byte) time_s;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_LowPassFilterTimes(final int time_s_x, final int time_s_y, final int time_s_z){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_FILTER_TIME;
        cmdVal[2] = (byte) time_s_x;
        cmdVal[3] = (byte) time_s_y;
        cmdVal[4] = (byte) time_s_z;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_calibrate_X_Axis(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x01;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x00;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_calibrate_Y_Axis(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x01;
        cmdVal[4] = (byte) 0x00;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_calibrate_Z_Axis(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x01;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_calibrate_XYZ(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x01;
        cmdVal[3] = (byte) 0x01;
        cmdVal[4] = (byte) 0x01;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetPeripheral_X_Axis_calibration(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x02;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x00;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetPeripheral_Y_Axis_calibration(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x02;
        cmdVal[4] = (byte) 0x00;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetPeripheral_Z_Axis_calibration(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x02;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_X_Axis_inverted(final boolean inv_enabled){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x00;

        if (inv_enabled){
            cmdVal[2] = 0x04;
        }else {
            cmdVal[2] = 0x03;
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_Y_Axis_inverted(final boolean inv_enabled){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x00;

        if (inv_enabled){
            cmdVal[3] = 0x04;
        }else {
            cmdVal[3] = 0x03;
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_Z_Axis_inverted(final boolean inv_enabled){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;
        cmdVal[2] = (byte) 0x00;
        cmdVal[3] = (byte) 0x00;
        cmdVal[4] = (byte) 0x00;

        if (inv_enabled){
            cmdVal[4] = 0x04;
        }else {
            cmdVal[4] = 0x03;
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_XYZ_Axis_inverted(final boolean x_inv_enabled, final boolean y_inv_enabled, final boolean z_inv_enabled){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_CALIB;

        if (x_inv_enabled){
            cmdVal[2] = 0x04;
        }else {
            cmdVal[2] = 0x03;
        }

        if (y_inv_enabled){
            cmdVal[3] = 0x04;
        }else {
            cmdVal[3] = 0x03;
        }

        if (z_inv_enabled){
            cmdVal[4] = 0x04;
        }else {
            cmdVal[4] = 0x03;
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_currentTime(Date date){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_SET_CURRENT_TIME;

        byte[] dateBytes = longToBytes(date.getTime());
        for (int i = 0; i < dateBytes.length; i++) {
            cmdVal[2+i] = dateBytes[i];
        }
        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_readCurrentTimeRequest(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_READ_CURRENT_TIME;
        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_RadioPoert(kRadioPowerLevel radioPower){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_SET_RADIO_POWER;
        int dBLevel = 0;
        switch (radioPower){
            case kRadioPowerLevel_Default_00_dB:
                dBLevel = 0;
                break;
            case kRadioPowerLevel_Highest_04_dB:
                dBLevel = 4;
                break;
            case kRadioPowerLevel_Low_neg_04_dB:
                dBLevel = -4;
                break;
            case kRadioPowerLevel_Lower_0_neg_08_dB:
                dBLevel = -8;
                break;
            case kRadioPowerLevel_Lower_1_neg_12_dB:
                dBLevel = -12;
                break;
            case kRadioPowerLevel_Lower_2_neg_16_dB:
                dBLevel = -16;
                break;
            case kRadioPowerLevel_Lowest_neg_20_dB:
                dBLevel = -20;
                break;
            default:
                dBLevel = 0;
                break;
        }

        cmdVal[2] = (byte) dBLevel;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_readRadioPowerRequest(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_READ_RADIO_POWER;
        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_setNewUserPin(final byte forUserRole, final int newPIN){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_FSEC_SET_NEW_PIN;

        byte[] bytePin = getByte2FromInt(newPIN);
        cmdVal[2] = forUserRole;
        cmdVal[3] = bytePin[0];
        cmdVal[4] = bytePin[1];

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_AxisMode_X(final byte mode, final byte flavor, final byte rsDependent){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_MODE;

        cmdVal[2]  = mode;
        cmdVal[3]  = flavor;
        cmdVal[4]  = (byte) 0xff;
        cmdVal[5]  = (byte) 0xff;
        cmdVal[6]  = (byte) 0xff;
        cmdVal[7]  = (byte) 0xff;
        cmdVal[8]  = rsDependent;
        cmdVal[9]  = (byte) 0xff;
        cmdVal[10] = (byte) 0xff;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_AxisMode_Y(final byte mode, final byte flavor, final byte rsDependent){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_MODE;

        cmdVal[2]  = (byte) 0xff;
        cmdVal[3]  = (byte) 0xff;
        cmdVal[4]  = mode;
        cmdVal[5]  = flavor;
        cmdVal[6]  = (byte) 0xff;
        cmdVal[7]  = (byte) 0xff;
        cmdVal[8]  = (byte) 0xff;
        cmdVal[9]  = rsDependent;
        cmdVal[10] = (byte) 0xff;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_AxisMode_Z(final byte mode, final byte flavor, final byte rsDependent){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_MODE;

        cmdVal[2]  = (byte) 0xff;
        cmdVal[3]  = (byte) 0xff;
        cmdVal[4]  = (byte) 0xff;
        cmdVal[5]  = (byte) 0xff;
        cmdVal[6]  = mode;
        cmdVal[7]  = flavor;
        cmdVal[8]  = (byte) 0xff;
        cmdVal[9]  = (byte) 0xff;
        cmdVal[10] = rsDependent;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_AxisModes(final byte xmode, final byte xflavor, final byte xRsDependent,
                                        final byte ymode, final byte yflavor, final byte yRsDependent,
                                        final byte zmode, final byte zflavor, final byte zRsDependent){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_MODE;

        cmdVal[2]  = xmode;
        cmdVal[3]  = xflavor;
        cmdVal[4]  = ymode;
        cmdVal[5]  = yflavor;
        cmdVal[6]  = zmode;
        cmdVal[7]  = zflavor;
        cmdVal[8]  = xRsDependent;
        cmdVal[9]  = yRsDependent;
        cmdVal[10] = zRsDependent;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_axisBoundary_X(final int topBound, final int botBound){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_BOUNDS;

        byte[] top = getByte2FromInt(topBound);
        byte[] bot = getByte2FromInt(botBound);

        cmdVal[2]  = top[0];
        cmdVal[3]  = top[1];
        cmdVal[4]  = bot[0];
        cmdVal[5]  = bot[1];
        cmdVal[6]  = (byte) 0xff;
        cmdVal[7]  = (byte) 0xff;
        cmdVal[8]  = (byte) 0xff;
        cmdVal[9]  = (byte) 0xff;
        cmdVal[10] = (byte) 0xff;
        cmdVal[11] = (byte) 0xff;
        cmdVal[12] = (byte) 0xff;
        cmdVal[13] = (byte) 0xff;


        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_axisBoundary_Y(final int topBound, final int botBound){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_BOUNDS;

        byte[] top = getByte2FromInt(topBound);
        byte[] bot = getByte2FromInt(botBound);

        cmdVal[2]  = (byte) 0xff;
        cmdVal[3]  = (byte) 0xff;
        cmdVal[4]  = (byte) 0xff;
        cmdVal[5]  = (byte) 0xff;
        cmdVal[6]  = top[0];
        cmdVal[7]  = top[1];
        cmdVal[8]  = bot[0];
        cmdVal[9]  = bot[1];
        cmdVal[10] = (byte) 0xff;
        cmdVal[11] = (byte) 0xff;
        cmdVal[12] = (byte) 0xff;
        cmdVal[13] = (byte) 0xff;


        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_axisBoundary_Z(final int topBound, final int botBound){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_BOUNDS;

        byte[] top = getByte2FromInt(topBound);
        byte[] bot = getByte2FromInt(botBound);

        cmdVal[2]  = (byte) 0xff;
        cmdVal[3]  = (byte) 0xff;
        cmdVal[4]  = (byte) 0xff;
        cmdVal[5]  = (byte) 0xff;
        cmdVal[6]  = (byte) 0xff;
        cmdVal[7]  = (byte) 0xff;
        cmdVal[8]  = (byte) 0xff;
        cmdVal[9]  = (byte) 0xff;
        cmdVal[10] = top[0];
        cmdVal[11] = top[1];
        cmdVal[12] = bot[0];
        cmdVal[13] = bot[1];


        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_axisInertiaTimeThresh(final int startX, final int endX,
                                                    final int startY, final int endY,
                                                    final int startZ, final int endZ,
                                                    final int startRS, final int endRS){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_AXIS_THRESH_TIME;

        byte[] bVal = getByte2FromInt(startRS);
        cmdVal[2]  = bVal[0];
        cmdVal[3]  = bVal[1];

        bVal = getByte2FromInt(endRS);
        cmdVal[4]  = bVal[0];
        cmdVal[5]  = bVal[1];

        bVal = getByte2FromInt(startX);
        cmdVal[6]  = bVal[0];
        cmdVal[7]  = bVal[1];

        bVal = getByte2FromInt(endX);
        cmdVal[8]  = bVal[0];
        cmdVal[9]  = bVal[1];

        bVal = getByte2FromInt(startY);
        cmdVal[10]  = bVal[0];
        cmdVal[11]  = bVal[1];

        bVal = getByte2FromInt(endY);
        cmdVal[12]  = bVal[0];
        cmdVal[13]  = bVal[1];

        bVal = getByte2FromInt(startZ);
        cmdVal[14]  = bVal[0];
        cmdVal[15]  = bVal[1];

        bVal = getByte2FromInt(endZ);
        cmdVal[16]  = bVal[0];
        cmdVal[17]  = bVal[1];

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_newLocalName(final String name){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_BASIC_LOCALNAME;

        String shortenedName = null;

        if (name.length() > 11){
            shortenedName = name.substring(0, 10);
        }else{
            shortenedName = name;
        }

        for (int i = 0; i < shortenedName.length(); i++) {
            cmdVal[2+i] = shortenedName.getBytes()[i];
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_newMinor(int minor){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_BASIC_MINOR;

        byte[] val = getByte2FromInt(minor);

        cmdVal[2] = val[0];
        cmdVal[3] = val[1];

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_newMajor(int major){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_BASIC_MAJOR;

        byte[] val = getByte2FromInt(major);

        cmdVal[2] = val[0];
        cmdVal[3] = val[1];

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_newTXPowerReference_1m_dB(int txPower){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_BASIC_TXPOWER;

        cmdVal[2] = (byte) txPower;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_newUUID(String uuidString){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_BASIC_UUID;

        byte[]bUUID = uuidString.getBytes();
        for (int i = 0; i < bUUID.length; i++) {
            cmdVal[2+i] = bUUID[i];
        }

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_bootloaderCommand(){
        byte[] cmdVal = new byte[2];
        cmdVal[0] = (byte)0xCA;
        cmdVal[1] = (byte)0xFE;

        if (bootCharacteristic != null && mGatt != null){
            bootCharacteristic.setValue(cmdVal);
            mGatt.writeCharacteristic(bootCharacteristic);
        }
    }

    public void setPeripheral_rebootCommand(){
        byte[] cmdVal = new byte[2];
        cmdVal[0] = (byte)0xFE;
        cmdVal[1] = (byte)0xCA;

        if (bootCharacteristic != null && mGatt != null){
            bootCharacteristic.setValue(cmdVal);
            mGatt.writeCharacteristic(bootCharacteristic);
        }
    }

    public void setPeripheral_dfuActivateCommand(){
        byte[] cmdVal = new byte[2];
        cmdVal[0] = (byte)0xDD;
        cmdVal[1] = (byte)0xDD;

        if (bootCharacteristic != null && mGatt != null){
            bootCharacteristic.setValue(cmdVal);
            mGatt.writeCharacteristic(bootCharacteristic);
        }
    }

    public void setPeripheral_saveChangesInFlashCommand(){
        byte[] cmdVal = new byte[2];
        cmdVal[0] = (byte)0xAB;
        cmdVal[1] = (byte)0xCD;

        if (bootCharacteristic != null && mGatt != null){
            bootCharacteristic.setValue(cmdVal);
            mGatt.writeCharacteristic(bootCharacteristic);
        }
    }



    private void sendCommandCharValue(final byte[] data) throws Exception {
        if (commandCharacteristic != null && mGatt != null){
            commandCharacteristic.setValue(data);
            mGatt.writeCharacteristic(commandCharacteristic);
        }else{
            throw new Exception(new String("Command characteristic not available!"));
        }
    }

    public void setPeripheral_startEEPTransfer(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_EEPROM_TRANSPORT;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_EEPSelftest(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_EEPROM_SELF_TEST;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPeripheral_requestAxisConfiguration(){
        byte[] cmdVal = new byte[20];
        cmdVal[0] = WRITE;
        cmdVal[1] = CMD_READ_AXIS_CONFIG;

        try {
            sendCommandCharValue(cmdVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    /* LISTENER INTERFACE */
    public interface CounterHDControllerListener {

        public void cc_didFindPeripherals(final List<BluetoothDevice> peripheralList);
        public void cc_didChangeConnectionState(final BluetoothGatt gatt,final int status,final int newState);
        public void cc_didReadCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic);
        public void cc_didChangeCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic);

        // basic Info
        public void cc_didUpdateBluetoothLocalName(final String name);
        public void cc_didUpdateMajorValue(final Integer major);
        public void cc_didUpdateMinorValue(final Integer minor);
        public void cc_didUpdateTXPower(final Integer txPower);

        // uuid
        public void cc_didUpdateUUID(final String uuid);

        // battery information
        public void cc_didUpdateBatteryInfo(final Integer chargePercent, boolean dcdcEndabled);

        // button state
        public void cc_didUpdateButton_1_state(boolean active);
        public void cc_didUpdateButton_2_state(boolean active);
        public void cc_didUpdateButton_3_state(boolean active);
        public void cc_didUpdateButton_4_state(boolean active);

        // Event Totals
        public void cc_didUpdateEventTotals(final Integer xEventCount, final Integer xEventTime, final Integer xProcessCount,
                                            final Integer yEventCount, final Integer yEventTime, final Integer yProcessCount,
                                            final Integer zEventCount, final Integer zEventTime, final Integer zProcessCount);

        // Device State
        public void cc_didUpdateDeviceType(final Integer devType);
        public void cc_didUpdateDeviceRevision(final Integer devRevision);
        public void cc_didUpdateBuildNumber(final Integer buildNumber);
        public void cc_didUpdateRS_Count(final Integer rs_Count);
        public void cc_didUpdateRS_Time(final Integer rs_Time);
        public void cc_didUpdateFirmwareVersion(final Integer major, final Integer minor);
        public void cc_didUpdateUserRole(final Integer userRole);
        public void cc_didUpdateStatusFlags(final  Integer statusFlags);

        // LIS Accellerometer
        public  void cc_didUpdateAccelerometerValues(final Integer xInclination, final Integer xCorrectedInclination, final Integer xGravity,
                                                     final Integer yInclination, final Integer yCorrectedInclination, final Integer yGravity,
                                                     final Integer zInclination, final Integer zCorrectedInclination, final Integer zGravity,
                                                     final Integer zFrequencyBin1,final Integer zFrequencyBin2,final Integer zFrequencyBin3,final Integer zFrequencyBin4 );

        // ParamTransport

        // Axis Config
        public void cc_didUpdateAxisConfiguration(final int axis, final int mode, final int flavor,
                                                  final int filterTime, final int isInverted, final int isRSDependent,
                                                  final int topBound, final int botBound,
                                                  final int topInertia, final int botInertia);
        public void cc_didUpdateEEPROMSelftestResult(final int errorCount, final byte[] testResultBytes);
        public void cc_didUpdatePeripheralTimeDate(final Date date);
        public void cc_didUpdateRadioPower(final int radioPower);





    }


    // Helping methods

    /**
     * @return Returns <b>true</b> if property is writable
     */
    private static boolean isCharacteristicWriteable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    /**
     * @return Returns <b>true</b> if property is Readable
     */
    private static boolean isCharacterisitcReadable(BluetoothGattCharacteristic pChar) {
        return ((pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0);
    }

    /**
     * @return Returns <b>true</b> if property is supports notification
     */
    private boolean isCharacterisiticNotifiable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    private int getIntFromBytes(byte[] bytes, int start, int length)
    {
        int val = 0;
        switch (length){
            case 1:
                val = bytes[start];
                break;
            case 2:
                val = ((bytes[start +1] & 0xff) << 8) | (bytes[start] & 0xff);
                break;
            case 4:
                val = ((bytes[start +3] & 0xff) << 24) | ((bytes[start+2] & 0xff) << 16) | ((bytes[start+1] & 0xff) << 8) | (bytes[start] & 0xff);
        }
        return val;
    }

    private int getSIntFromBytes(byte[] bytes, int start, int length)
    {
        int val = 0;
        switch (length){
            case 1:
                val = bytes[start];
                break;
            case 2:
                val = ((bytes[start +1] ) << 8) | (bytes[start] );
                break;
            case 4:
                val = ((bytes[start +3] ) << 24) | ((bytes[start+2] ) << 16) | ((bytes[start+1] ) << 8) | (bytes[start] );
        }
        return val;
    }

    private byte[] getByte2FromInt(int val){
        byte[] retVal = new byte[2];
        retVal[0] = (byte) (val & 0xFF);
        retVal[1] = (byte) ((val >> 8) & 0xFF);
        return retVal;
    }

    public byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    public long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.put(bytes);
        buffer.flip();//need flip
        return buffer.getLong();
    }
}
