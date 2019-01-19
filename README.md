# <bindingName> Binding

This binding allows you to use IP cameras in Openhab 2 so long as the camera has the ability to fetch a snapshot (JPG file) via a http link. It does not yet support RTSP streams (see the issue thread at this github project for more info) but the Netty library was chosen as it can be used to provide this support in the future. If the brand does not have a full API then the camera will only fetch a picture and will not have any support for alarms or any of the other cool features that the binding has implemented for certain brands. Each brand that does have an API will have different features, as each API is different and hence the support in this binding will also differ. Choose your camera wisely by looking at what the APIs allow you to do. 

In Alphabetical order the brands that have an API are:

**AMCREST**

https://s3.amazonaws.com/amcrest-files/Amcrest+HTTP+API+3.2017.pdf

**DAHUA**

ftp://ftp.wintel.fi/drivers/dahua/SDK-HTTP_ohjelmointi/DAHUA_IPC_HTTP_API_V1.00x.pdf

**FOSCAM**

https://www.foscam.es/descarga/Foscam-IPCamera-CGI-User-Guide-AllPlatforms-2015.11.06.pdf

**HIKVISION**

oversea-download.hikvision.com/uploadfile/Leaflet/ISAPI/HIKVISION%20ISAPI_2.0-IPMD%20Service.pdf

**INSTAR**

https://wikiold.instar.de/index.php/List_of_CGI_commands_(HD)



## Supported Things

If doing manual text configuration and/or when needing to setup HABPANEL/sitemap you are going to need to know what your camera is as a "thing type". These are listed in CAPS below and are only a single word. Example: The thing type for a generic onvif camera is "ONVIF".

HTTPONLY: For any camera that is not ONVIF compatible, and has the ability to fetch a snapshot with a url.

ONVIF: Use for all ONVIF Cameras from any brand that do not have an API. You gain PTZ and auto finding of the snapshot url over httponly things. If your camera does not have PTZ you may prefer to set it up as httponly due to a faster connection time as onvif is skipped.

AMCREST: Use for all Amcrest Cameras that do not work as a dahua thing as this uses an older polling method for alarm detection which is not as efficient as the newer method used in dahua. Amcrest are made by Dahua and hence their cameras can be setup as a Dahua thing.

AXIS: Use for all current Axis Cameras as they support ONVIF.

DAHUA: Use for all current Dahua and Amcrest cameras as they support an API as well as ONVIF.

FOSCAM: Use for all current FOSCAM HD Cameras as they support an API as well as ONVIF.

HIKVISION: Use for all current HIKVISION Cameras as they support an API as well as ONVIF.

INSTAR: Use for all current INSTAR Cameras as they support an API as well as ONVIF.


## Discovery

Auto discovery is not supported currently and I would love a PR if someone has experience finding cameras on a network. ONVIF documents a way to use UDP multicast to find cameras. Currently you need to manually add the IP camera either via PaperUI or textual configuration which is covered below in more detail. Once the camera is added you then supply the IP address and port settings for the camera. Optionally a username and password can also be filled in if the camera is secured with these, which I highly recommend. Clicking on the pencil icon in PaperUI is how you reach these parameters and how you make all the settings unless you have chosen to use manual text configuration. You can not mix manual and PaperUI methods, but it is handy to see and read the descriptions of all the controls in PaperUI.

## Binding Configuration

The binding can be configured with PaperUI by clicking on the pencil icon of any of the cameras that you have manually added via the PaperUI inbox by pressing on the PLUS (+) icon. 

It can also be manually configured with text files by doing the following. DO NOT try and change a setting using PaperUI after using textual configuration as the two will conflict as the text file locks the settings preventing them from changing. Because the binding is changing so much at the moment I would recommend you use textual configuration, as each time Openhab restarts it removes and adds the camera so you automatically gain any extra channels that I add. If using PaperUI, each time I add a new channel you need to remove and re-add the camera which then gives it a new UID number (Unique ID number), which in turn can break your sitemap and HABPanel setups. Textual configuration has its advantages and locks the camera to use a simple UID.

The parameters that can be used in textual configuration are:

IPADDRESS

PORT

ONVIF_PORT

USERNAME

PASSWORD

ONVIF_MEDIA_PROFILE

POLL_CAMERA_MS

SNAPSHOT_URL_OVERIDE

IMAGE_UPDATE_EVENTS

NVR_CHANNEL

MOTION_URL_OVERIDE



Create a file called 'ipcamera.things' and save it to your things folder. Inside this file enter this in plain text and modify it to your needs...


```
Thing ipcamera:DAHUA:001 [IPADDRESS="192.168.1.5", PASSWORD="suitcase123456", USERNAME="admin", POLL_CAMERA_MS=2000]

Thing ipcamera:HIKVISION:002 [IPADDRESS="192.168.1.6", PASSWORD="suitcase123456", USERNAME="admin", POLL_CAMERA_MS=2000]

Thing ipcamera:ONVIF:003 [ IPADDRESS="192.168.1.21", PASSWORD="suitcase123456", USERNAME="admin", ONVIF_PORT=80, PORT=80, POLL_CAMERA_MS=2000]

Thing ipcamera:HTTPONLY:004 [ IPADDRESS="192.168.1.22", PASSWORD="suitcase123456", USERNAME="admin", SNAPSHOT_URL_OVERIDE="http://192.168.1.22/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2&usr=admin&pwd=suitcase123456", PORT=80, POLL_CAMERA_MS=2000]

```


Here you see the format is: bindingID:THINGTYPE:UID [param1="string",param2=x,param3=x]


BindingID: is always ipcamera.

THINGTYPE: is found listed above under the heading "supported things"

UID: Can be made up but it must be UNIQUE, hence why it is called uniqueID. If you use PaperUI you will notice the UID will be something like "0A78687F" which is not very nice when using it in sitemaps and rules, also paperui will choose a new random ID each time you remove and add the camera causing you to edit your rules, items and sitemaps to make them match. 


## Thing Configuration

**IMAGE_UPDATE_EVENTS**

If you look in PaperUI you will notice the numbers are in brackets after each option, remember the number that represents the option you wish to use and enter this into the thing file which is described above.

## Channels

See PaperUI for a full list of channels and the descriptions. Each camera brand will have different channels depending on how much of the support for an API has been added. The channels are kept consistent as much as possible from brand to brand when possible to make upgrading to a different branded camera easier without the need to edit your rules as much.

## Full Example

Use the following examples to base your setup on to save some time. NOTE: If your camera is secured with a user and password the links will not work and you will have to use the IMAGE channel to see a picture. FOSCAM cameras are the exception to this as they use the user and pass in plain text in the URL. In the example below you need to leave a fake address in the "Image url=" line otherwise it does not work, the item= overrides the url. Feel free to let me know if this is wrong or if you find a better way.

NOTE: If you used paperUI to create the camera thing instead of textual config, you will need to ensure the 001 is replaced with the cameras UID which may look like "0A78687F". Also replace AMCREST or HIKVISION with the name of the supported thing you are using from the list above.


                

*.things


```

Thing ipcamera:DAHUA:001 [IPADDRESS="192.168.1.5", PASSWORD="suitcase123456", USERNAME="admin", POLL_CAMERA_MS=2000]

Thing ipcamera:HIKVISION:002 [IPADDRESS="192.168.1.6", PASSWORD="suitcase123456", USERNAME="admin", POLL_CAMERA_MS=2000]

```



*.items

```

Image BabyCamImage { channel="ipcamera:DAHUA:001:image" }
Switch BabyCamUpdateImage "Get new picture" { channel="ipcamera:DAHUA:001:updateImageNow" }
Number BabyCamDirection "Camera Direction"
Dimmer BabyCamPan "Pan left/right" { channel="ipcamera:DAHUA:001:pan" }
Dimmer BabyCamTilt "Tilt up/down" { channel="ipcamera:DAHUA:001:tilt" }
Dimmer BabyCamZoom "Zoom in/out" { channel="ipcamera:DAHUA:001:zoom" }
Switch BabyCamEnableMotion "MotionAlarm on/off" { channel="ipcamera:DAHUA:001:enableMotionAlarm" }
Switch BabyCamMotionAlarm "Motion detected" { channel="ipcamera:DAHUA:001:motionAlarm" }
Switch BabyCamEnableAudioAlarm "AudioAlarm on/off" { channel="ipcamera:DAHUA:001:enableAudioAlarm" }
Switch BabyCamAudioAlarm "Audio detected" { channel="ipcamera:DAHUA:001:audioAlarm" }
Dimmer BabyCamAudioThreshold "Audio Threshold" { channel="ipcamera:DAHUA:001:thresholdAudioAlarm" }

Image CamImage { channel="ipcamera:HIKVISION:002:image" }
Switch CamUpdateImage "Get new picture" { channel="ipcamera:HIKVISION:002:updateImageNow" }
Switch CamEnableMotionAlarm "MotionAlarm on/off" { channel="ipcamera:HIKVISION:002:enableMotionAlarm" }
Switch CamMotionAlarm "Motion detected" { channel="ipcamera:HIKVISION:002:motionAlarm" }
Switch CamEnableLineAlarm "LineAlarm on/off" { channel="ipcamera:HIKVISION:002:enableLineCrossingAlarm" }
Switch CamLineAlarm "Line Alarm detected" { channel="ipcamera:HIKVISION:002:lineCrossingAlarm" }

```


*.sitemap

```

        Text label="BabyMonitor" icon="camera"{
            Switch item=BabyMonitor label="Baby Monitor Rules"
            Image url="http://google.com/leaveLinkAsThis" item=BabyCamImage refresh=2000
            Switch item=BabyCamDirection label="Camera Direction" mappings=[0="Door", 1="Cot", 2="Room"]
            Switch item=BabyCamImage
            Slider item=BabyCamPan label="Pan [%d]"
            Slider item=BabyCamTilt label="Tilt [%d]"
            Slider item=BabyCamZoom label="Zoom [%d]"
            Switch item=BabyCamEnableMotion
            Switch item=BabyCamMotionAlarm
            Switch item=BabyCamEnableAudioAlarm
            Switch item=BabyCamAudioAlarm
            Slider item=BabyCamAudioThreshold label="Audio Threshold [%d]"
        }   
        Text label="Driveway Camera" icon="camera" 
        {   
            Image url="http://google.com/leaveLinkAsThis" item=CamImage refresh=2000
            Switch item=CamUpdateImage label="Fetch new picture of Driveway"
            Switch item=CamEnableMotionAlarm
            Switch item=CamMotionAlarm
            Switch item=CamEnableLineAlarm
            Switch item=CamLineAlarm        
        }

```

*.rules

```
rule "Move cameras direction"
    when
    Item BabyCamDirection changed
    then
    switch (BabyCamDirection.state as DecimalType) {
        case 0 :{
        //Door
        BabyCamPan.sendCommand(22)
        BabyCamTilt.sendCommand(60)
        BabyCamZoom.sendCommand(0)
        }
        case 1 :{
        //Cot
        BabyCamPan.sendCommand(22)
        BabyCamTilt.sendCommand(0)
        BabyCamZoom.sendCommand(0)
        }
        case 2 : {
        //Room
        BabyCamPan.sendCommand(15)
        BabyCamTilt.sendCommand(75)
        BabyCamZoom.sendCommand(1)
        }
    }
end

```


## Special notes for different brands

**Amcrest**

It is better to setup your AMCREST camera as a DAHUA thing type as the old alarm checking method is used in AMCREST and the newer method is used in DAHUA that is stream based. This means less CPU and load on your server if you setup as Dahua.

**Hikvision**
Each alarm you wish to use must have "Notify Surveillance Center" enabled under each alarms settings in the control panel of the camera itself. The API and also ONVIF are disabled by default on the cameras and also are needed to be enabled.

**Foscam**

These cameras need to have a detection area listed in the URL when you enable the motion alarm. As each model has a different resolution and two different URLs, this makes it difficult to make this automatic so an override feature was added to create your own enable the alarm url. This setting is called "MOTION_URL_OVERIDE" and the steps to using it are:

1. Enable the motion alarm in the web interface of your camera and setup any areas you wish movement to be ignored in ie. Tree branches moving in the wind.
2. Use any web browser to fetch this URL https://x.x.x.x/cgi-bin/CGIProxy.fcgi?cmd=getMotionDetectConfig1&usr=xxxxx&pwd=xxxxx
3. Use the information returned by the above url to create the override settings.

An example for a Foscam C2 is...

```
/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig1&isEnable=1&snapInterval=1&schedule0=281474976710655&schedule1=281474976710655&schedule2=281474976710655&schedule3=281474976710655&schedule4=281474976710655&schedule5=281474976710655&schedule6=281474976710655&x1=0&y1=0&width1=10000&height1=10000&sensitivity1=1&valid1=1&linkage=6&usr=xxxxx&pwd=xxxxx
```

Another example is: 

```
/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=1&linkage=0001&sensitivity=1&triggerInterval=15&schedule0=281474976710655&schedule1=281474976710655&schedule2=281474976710655&schedule3=281474976710655&schedule4=281474976710655&schedule5=281474976710655&schedule6=281474976710655&area0=1023&area1=1023&area2=1023&area3=1023&area4=1023&area5=1023&area6=1023&area7=1023&area7=1023&area8=1023&area9=1023&usr=username&pwd=password
```


**Instar**

These cameras have the ability to call the Openhab REST API directly when an alarm occurs hence why the binding does not have the alarm switches as the camera can handle this directly. See the openhab documentation regarding how to use the rest API and if you get this working please send me details how so I can include a more detailed setup guide here.


## Reducing log sizes

There are two log files discussed here, openhab.log and events.log please take the time to consider both logs if a fast and stable setup is something you care about. On some systems with slow disk access like SD cards, the writing of a log file can greatly impact on performance. We can turn on/up logs to fault find issues, and then disable them to get the performance back when everything is working.


To watch the logs in realtime with Linux based setups you can use this linux command which can be done via SSH with a program called putty from a windows or mac machine.

```

tail -f /var/log/openhab2/openhab.log -f /var/log/openhab2/events.log

```


CTRL+C will close the stream. You can also use SAMBA/network shares to open or copy the file directly, but my favorite way to view the logs is with "Frontail". Frontail is another UI that can be selected like paperUI, and can be installed using the openhabian config tool.


openhab.log This file displays the information from all bindings and can have the amount of information turned up or down on a per binding basis. The default level is INFO and is the middle level of 5 settings you can use. Openhab documentation goes into this in more detail. Using KARAF console you can use these commands to turn the logging up and down to suit your needs. If you are having issues with the binding not working with your camera, then TRACE will give me everything in DEBUG with the additional reply packets from the camera for me to use for fault finding.


```

log:set WARN org.openhab.binding.ipcamera

log:set INFO org.openhab.binding.ipcamera

log:set DEBUG org.openhab.binding.ipcamera

log:set TRACE org.openhab.binding.ipcamera

```


events.log By default Openhab will log all image updates as an event into a file called events.log, this file can quickly grow if you have multiple cameras all updating pictures every second. To reduce this if you do not want to switch to only updating the image on EVENTS like motion alarms, you then have 2 options. One is to turn off all events, the other is to filter the events before they reach the log file. Openhab does not allow normal filtering at a binding level due to the log being a pure output from the event bus. 

To disable the event.log use this command in Karaf.


```

log:set WARN smarthome.event

```

To re-enable use the same command with INFO instead of WARN. 

To filter out the events do the following:

```
sudo nano /var/lib/openhab2/etc/org.ops4j.pax.logging.cfg
```

Inside that file paste the following, save and then reboot.

```
############ CUSTOM FILTERS START HERE #################
# event log filter
log4j2.appender.event.filter.myfilter1.type = RegexFilter
log4j2.appender.event.filter.myfilter1.regex = .*changed from raw type.*
log4j2.appender.event.filter.myfilter1.onMatch = DENY
log4j2.appender.event.filter.myfilter1.onMisMatch = ACCEPT
################# END OF FILTERS ######################

```

You can specify the item name in the filter to remove just 1 camera, or you can use the above without the item name to remove all events from images updating which will be for other bindings as well.


## Roadmap for further development

Currently the focus is on stability, speed and creating a good framework that allows multiple brands to be used in RULES in a consistent way. What this means is hopefully the binding is less work to add a new API function to instead of people creating stand alone scripts which are not easy for new Openhab users to find or use. By consistent I mean if a camera breaks down and you wish to change brands, your rules with this binding should be easy to adapt to the new brand of camera with no/minimal changes. 


If you need a feature added that is in an API and you can not program, please raise an issue ticket here at this github project with a sample of what a browser shows when you enter in the URL and it is usually very quick to add these features. 

If you wish to contribute then please create an issue ticket first to discuss how things will work before doing any coding. This is for multiple reasons due to needing to keep things CONSISTENT between brands and also easy to maintain. This list of areas that could be added are a great place to start helping with this binding if you wish to contribute. Any feedback, push requests and ideas are welcome.



If this binding becomes popular, I can look at extending the frame work to support:

RTSP Video streams.

Auto find and setup cameras on your network.

PTZ methods for continuous move.

FTP/NAS features to save the images and delete old files.

ONVIF alarms

1 and 2 way audio.


