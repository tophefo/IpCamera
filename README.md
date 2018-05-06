# <bindingName> Binding

This binding allows you to use IP cameras in Openhab2 directly so long as they either have ONVIF or the ability to fetch a snapshot via a http link. Some brands of camera have much better support and also have motion and audio alarms working that can be used to trigger Openhab rules and do much more cool stuff so choose your camera wisely by looking at what the APIs allow and do not allow you to do. Keep a copy of the API documents.

In Alphabetical order:

AMCREST

https://s3.amazonaws.com/amcrest-files/Amcrest+HTTP+API+3.2017.pdf

AXIS

https://www.axis.com/en-au/support/developer-support/vapix

FOSCAM

https://www.foscam.es/descarga/Foscam-IPCamera-CGI-User-Guide-AllPlatforms-2015.11.06.pdf

HIKVISION

oversea-download.hikvision.com/uploadfile/Leaflet/ISAPI/HIKVISION%20ISAPI_2.0-IPMD%20Service.pdf

INSTAR

https://wikiold.instar.de/index.php/List_of_CGI_commands_(HD)



## Supported Things

If doing manual text configuration and/or when needing to setup HABPANEL or your sitemap you are going to need to know what your camera has as a "thing name". These are listed in BOLD below. 

NON_ONVIF: For any camera that is not ONVIF compatible, and has the ability to fetch a snapshot with a url.

ONVIF: Use for all ONVIF Cameras from any brand that do not have an API.

AMCREST: Use for all current Amcrest Cameras as they support an API as well as ONVIF.

AXIS: Use for all current Axis Cameras as they support an API as well as ONVIF.

FOSCAM: Use for all current FOSCAM Cameras as they support an API as well as ONVIF.

HIKVISION: Use for all current HIKVISION Cameras as they support an API as well as ONVIF.


## Discovery

Auto discovery is not supported currently and I would love a PR if someone has experience using io.Netty and UDP multicast which appears to be the way ONVIF uses to find cameras on a network. Currently you need to manually add the IP camera either via PaperUI or textual configuration which is covered below in more detail. Once the camera is added you then need to supply the IP address and port settings you wish to use. Optionally a username and password can also be filled in if the camera is secured with these which I highly recommend. Clicking on the pencil icon in PaperUI is how you reach these parameters and how you make all the settings unless you have chosen to use manual text configuration. You can not mix manual and PaperUI methods, but it is handy to see and read the descriptions of all the controls in PaperUI.

## Binding Configuration

The binding can be configured with PaperUI by clicking on the pencil icon of any of the cameras that you have manually added via the PaperUI inbox by pressing on the PLUS (+) icon. 

It can also be manually configured with text files by doing the following. DO NOT try and change a setting using PaperUI after using textual configuration as the two will conflict as the text file locks the settings preventing them from changing. Because the binding is changing so much at the moment I would recommend you use textual configuration as each time Openhab restarts it removes and adds the camera so you automatically gain any extra channels that I add. If using PaperUI, each time I add a new channel you need to remove and re-add the camera which then gives it a new UID number, which in turn can break your sitemap and HABPanel setups. Textual configuration has its advantages and locks the camera to use a simple UID (Unique ID number).

The parameters that can be used in textual configuration are:

IPADDRESS

PORT

ONVIF_PORT

USERNAME

PASSWORD

ONVIF_MEDIA_PROFILE

POLL_CAMERA_MS

USE_HTTPS (not working yet)

SNAPSHOT_URL_OVERIDE

IMAGE_UPDATE_EVENTS



Create a file called 'ipcamera.things' and save it to your things folder.


Thing ipcamera:ONVIF:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_MEDIA_PROFILE=0]


Here you see the format is: bindingID:THINGNAME:UID [param1="string",param2=x,param3=x]


BindingID: is always ipcamera.

THINGNAME: is found listed above under the heading "supported things"

UID: Can be made up but it must be UNIQUE, if you use PaperUI you will notice the UID will be something like "0A78687F" which is not very nice when using it in sitemaps and rules.


## Thing Configuration

After setting up the camera as per above, you can watch the log files when the camera connects as it will list the supported profiles and what each profile will give you. The log will also report if PTZ is supported by your camera.

## Channels

See PaperUI for a full list of channels and the descriptions. Each camera brand will have different channels depending on how much of the support for an API has been added. The channels are kept consistent as much as possible from brand to brand when possible to make upgrading to a different branded camera easier without the need to edit your rules as much.

## Full Example

Use the following examples to base your setup on to save some time. NOTE: If your camera is secured with a user and password the links will not work and you will have to use the IMAGE channel to see a picture. FOSCAM cameras are the exception to this as they use the user and pass in plain text in the URL. In the example below you need to leave a fake address in the "Image url=" line otherwise it does not work, the item= overrides the url. Feel free to let me know if this is wrong or if you find a better way.

NOTE: You need to ensure the 001 is replaced with the cameras UID which may look like "0A78687F" if you used PaperUI to add the camera. Also replace AMCREST with the name of the supported thing you are using from the list above.


*.sitemap

Slider item=ipcamera_AMCREST_001_pan

Slider item=ipcamera_AMCREST_001_tilt

Slider item=ipcamera_AMCREST_001_zoom

Switch item=ipcamera_AMCREST_001_enableMotionAlarm

Switch item=ipcamera_AMCREST_001_motionAlarm

Switch item=ipcamera_AMCREST_001_audioAlarm

Image url="http://google.com/leaveLinkAsThis" item=ipcamera_AMCREST_001_image refresh=5000
             
                

*.things

Thing ipcamera:AMCREST:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="DVadar", ONVIF_MEDIA_PROFILE=0]

## Roadmap for further development

If you need a feature added that is in an API, please raise an issue ticket here at this github project if you are not able to copy what I have already done and create a push request. I am looking at 2 way audio and how it can be best added to the binding, if you have ideas then please see the issue ticket I created to discuss how this can be done and how the features will work. If you wish to contribute then please create an issue ticket first to discuss how things will work before doing any coding on large amounts of changes. I am trying to setup the backend code to allow people that actually own a particular brand of camera to easily add new features and to insulate other brands from bugs. Any feedback and ideas are welcome.
