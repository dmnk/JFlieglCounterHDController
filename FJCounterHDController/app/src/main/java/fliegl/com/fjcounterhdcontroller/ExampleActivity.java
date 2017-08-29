package fliegl.com.fjcounterhdcontroller;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.support.annotation.LayoutRes;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

public class ExampleActivity extends AppCompatActivity implements JCounterHDController.CounterHDControllerListener {



    // Fields for CounterHDController
    JCounterHDController counterController;

    static String TAG = "Example: ";

    // User interface
    Button scanButton;
    Button disconnectButton;
    Button sendBootloaderButton;
    Button calibrateAxisButton;
    Button requestEEPSelftestButton;

    TextView textView;
    TextView inclinationTextView;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_example);

        // prevent from locking the devices screen
        getWindow().addFlags(FLAG_KEEP_SCREEN_ON);

        counterController = new JCounterHDController(this);

        //counterController.startScanning(true);

        scanButton = (Button) findViewById(R.id.ScanButton);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scanButtonPushed();
            }
        });
        disconnectButton = (Button) findViewById(R.id.disconnectButton);
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnectButtonPushed();
            }
        });

        sendBootloaderButton = (Button) findViewById(R.id.sendBootloader);
        sendBootloaderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (counterController != null)
                {
                    counterController.setPeripheral_bootloaderCommand();
                }
            }
        });

        calibrateAxisButton = (Button) findViewById(R.id.calibrateButton);
        calibrateAxisButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (counterController != null)
                {
                    counterController.setPeripheral_calibrate_XYZ();
                }
            }
        });

        requestEEPSelftestButton = (Button) findViewById(R.id.requestEEPTest);
        requestEEPSelftestButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (counterController != null)
                {
                    counterController.setPeripheral_EEPSelftest();
                }
            }
        });

        textView = (TextView) findViewById(R.id.textView);
        SimpleDateFormat sdf = new SimpleDateFormat("kk:mm:ss");

        textView.setText(String.format("Start: %s", sdf.format(new Date())));

        inclinationTextView = (TextView) findViewById(R.id.inclinationTextView);

    }

    void disconnectButtonPushed(){
        if (counterController != null)
        {
            counterController.disconnectPeripheral();
        }


    }

    void scanButtonPushed()
    {

        if (counterController != null)
        {
            counterController.setCounterHDControllerListener(this);
            counterController.startScanning(true);
        }
    }



    @Override
    public void cc_didFindPeripherals(final List<BluetoothDevice> peripheralList) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (peripheralList.size() > 0)
                {
                    Log.i(TAG, "Did find peripherals: " + peripheralList.size());
                    textView.append("\nFound peripherals: " + peripheralList.size());

                    // For Simplicity - I only retrieve the first peripheral and automagically
                    // connect to it. In real life applications you may want to populate a list view
                    // and connect to a peripheral only if the user touches the list view entry.

                    //BluetoothDevice d = peripheralList.get(0);
                    //counterController.connectPeripheral(d);

                    // ** or **

                    // only use peripheral in test lab with HardwareAddress: D2:59:7E:05:21:88
                    for (BluetoothDevice d :
                            peripheralList) {
                        //Log.i(TAG, d.getAddress().toString());
                        if (d.getAddress().toString().equalsIgnoreCase("D2:59:7E:05:21:88")){
                            counterController.connectPeripheral(d);
                        }
                    }
                }
            }
        });



    }

    @Override
    public void cc_didChangeConnectionState(final BluetoothGatt gatt, final int status, final int newState)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Did change connection state: " + status + newState);
                textView.append("\nDid change connection state: " + status + newState);
            }
        });

    }

    @Override
    public void cc_didReadCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, String.format("Did read a value: %s for Characteristic: %s", characteristic.getValue().toString(), characteristic.getUuid().toString()));
                //textView.append(String.format("\nDid read a value: %s for Characteristic: %s", characteristic.getValue().toString(), characteristic.getUuid().toString()));
            }
        });

    }

    @Override
    public void cc_didChangeCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, String.format("Did notify chage of a value: %s for Characteristic: %s", characteristic.getValue().toString(), characteristic.getUuid().toString()));
                //textView.append(String.format("\nNotified value change: %s for Characteristic: %s", characteristic.getValue().toString(), characteristic.getUuid().toString()));
            }
        });

    }

    @Override
    public void cc_didUpdateBluetoothLocalName(final String name) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nName: %s", name));
            }
        });
    }

    @Override
    public void cc_didUpdateMajorValue( final Integer major) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nMajor: %d", major));
            }
        });
    }

    @Override
    public void cc_didUpdateMinorValue(final Integer minor) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nMinor: %d", minor));
            }
        });
    }

    @Override
    public void cc_didUpdateTXPower(final Integer txPower) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nTX-Power: %d", txPower));
            }
        });
    }

    @Override
    public void cc_didUpdateUUID(final String uuid)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nUUID: %s", uuid));
            }
        });
    }

    @Override
    public void cc_didUpdateBatteryInfo(final Integer chargePercent, final boolean dcdcEndabled)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nBattery: %d (DCDC: %s)", chargePercent, dcdcEndabled));
            }
        });
    }

    @Override
    public void cc_didUpdateButton_1_state(final boolean active)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (active){
                    textView.append("\nButton 1 ACTIVE");
                }else {
                    textView.append("\nButton 1 inactive");
                }

            }
        });
    }
    @Override
    public void cc_didUpdateButton_2_state(final boolean active)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (active){
                    textView.append("\nButton 2 ACTIVE");
                }else {
                    textView.append("\nButton 2 inactive");
                }

            }
        });
    }
    @Override
    public void cc_didUpdateButton_3_state(final boolean active)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (active){
                    textView.append("\nButton 3 ACTIVE");
                }else {
                    textView.append("\nButton 3 inactive");
                }

            }
        });
    }
    @Override
    public void cc_didUpdateButton_4_state(final boolean active)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (active){
                    textView.append("\nButton 4 ACTIVE");
                }else {
                    textView.append("\nButton 4 inactive");
                }

            }
        });
    }

    @Override
    public void cc_didUpdateEventTotals(final Integer xEventCount, final Integer xEventTime, final Integer xProcessCount,
                                        final Integer yEventCount, final Integer yEventTime, final Integer yProcessCount,
                                        final Integer zEventCount, final Integer zEventTime, final Integer zProcessCount){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nX-Axis Event-Count:   %d", xEventCount));
                textView.append(String.format("\nX-Axis Time (min.):   %d", xEventTime));
                textView.append(String.format("\nX-Axis Process-Count: %d", xProcessCount));
                textView.append(String.format("\nY-Axis Event-Count:   %d", yEventCount));
                textView.append(String.format("\nY-Axis Time (min.):   %d", yEventTime));
                textView.append(String.format("\nY-Axis Process-Count: %d", yProcessCount));
                textView.append(String.format("\nZ-Axis Event-Count:   %d", zEventCount));
                textView.append(String.format("\nZ-Axis Time (min.):   %d", zEventTime));
                textView.append(String.format("\nZ-Axis Process-Count: %d", zProcessCount));
            }
        });
    }

    @Override
    public void cc_didUpdateDeviceType(final Integer devType)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nDeviceType: %d", devType));
            }
        });
    }

    @Override
    public void cc_didUpdateDeviceRevision(final Integer devRevision)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nDevice Revision: %d", devRevision));
            }
        });
    }

    @Override
    public void cc_didUpdateBuildNumber(final Integer buildNumber)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nBuildNr.: %d", buildNumber));
            }
        });
    }

    @Override
    public void cc_didUpdateRS_Count(final Integer rs_Count)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nRS Count: %d", rs_Count));
            }
        });
    }

    @Override
    public void cc_didUpdateRS_Time(final Integer rs_Time)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nRS Time: %d", rs_Time));
            }
        });
    }

    @Override
    public void cc_didUpdateFirmwareVersion(final Integer major, final Integer minor)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nFirmware: %d.%d", major, minor));
            }
        });
    }

    @Override
    public void cc_didUpdateUserRole(final Integer userRole)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nUser-Role: %d", userRole));
            }
        });
    }

    @Override
    public void cc_didUpdateStatusFlags(final  Integer statusFlags)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nStatus-Flags: %d", statusFlags));
            }
        });
    }

    @Override
    public  void cc_didUpdateAccelerometerValues(final Integer xInclination, final Integer xCorrectedInclination, final Integer xGravity,
                                                 final Integer yInclination, final Integer yCorrectedInclination, final Integer yGravity,
                                                 final Integer zInclination, final Integer zCorrectedInclination, final Integer zGravity,
                                                 final Integer zFrequencyBin1,final Integer zFrequencyBin2,final Integer zFrequencyBin3,final Integer zFrequencyBin4 )
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                inclinationTextView.setText(String.format("X: %d° Y: %d° Z: %d°", xCorrectedInclination, yCorrectedInclination, zCorrectedInclination));
                textView.append(String.format("\nInclination:\nX: %d° Y: %d° Z: %d° \nCorrected:\nX: %d° Y: %d° Z: %d° ",xInclination, yInclination, zInclination, xCorrectedInclination, yCorrectedInclination, zCorrectedInclination ));
            }
        });
    }

    @Override
    public void cc_didUpdateAxisConfiguration(final int axis, final int mode, final int flavor,
                                              final int filterTime, final int isInverted, final int isRSDependent,
                                              final int topBound, final int botBound,
                                              final int topInertia, final int botInertia) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nReceived Axis-Config: %d %d %d %d %d %d %d %d %d %d ", axis, mode, flavor, filterTime, isInverted, isRSDependent, topBound, botBound, topInertia, botInertia));
            }
        });
    }

    @Override
    public void cc_didUpdateEEPROMSelftestResult(final int errorCount, final byte[] testResultBytes) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nReceived EEEPRom Testresult with %d errors.", errorCount));

                showToastWithString(String.format("\nReceived EEEPRom Testresult with %d errors.", errorCount));
            }
        });
    }

    @Override
    public void cc_didUpdatePeripheralTimeDate(final Date date) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nReceived Peripherals Date: %s", date.toString()));
            }
        });
    }

    @Override
    public void cc_didUpdateRadioPower(final int radioPower){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(String.format("\nReceived Radio Power: %d dBm", radioPower));
            }
        });
    }

    @Override
    public void cc_didUpdateTemperature(final Integer temperature){

    }

    private void showToastWithString(String s){
        Toast.makeText(getApplicationContext(), s , Toast.LENGTH_SHORT).show();
    }


}
