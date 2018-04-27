# <bindingName> Binding

This binding allows you to use IP cameras in Openhab2 directly.

## Supported Things

NON_ONVIF: For any camera that is not ONVIF compatible, yet has the ability to fetch a snapshot with a url.

ONVIF: Use for all ONVIF Cameras from any brand that do not have an API.

AMCREST: Use for all current Amcrest Cameras as they support an API as well as ONVIF.

FOSCAM: Use for all current FOSCAM Cameras as they support an API as well as ONVIF.

AXIS: Use for all current Axis Cameras as they support an API as well as ONVIF


## Discovery

Auto discovery is not supported currently. Manually add the IP camera either via PaperUI or textual configuration which is covered below in more detail. Once the camera is added you then need to supply the IP address and port settings you wish to use. Optionally a username and password can also be filled in if the camera is secured with these. Clicking on the pencil icon in PaperUI is how you reach these parameters.

## Binding Configuration

The binding can be configured with PaperUI by clicking on the pencil icon of any of the cameras that you have manually added. 

It can also be manually configured with text files by doing the following. DO NOT try and change a setting using PaperUI after using textual configuration as the two will conflict as the text file locks the settings preventing them from changing. Because the binding is changing so much at the moment I would recommend you only use paperUI and each time you upgrade to a newer version you remove and re-add the camera.

The parameters that can be used are:

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




## Thing Configuration

After setting up the camera as per above, you will need to watch the log files where when the camera connects as it will list the supported profiles and what each profile will give you. My Amcrest camera needed to have an extra profile (substream) turned on and set to MJPG as this was not possible on the default profile 0 (Mainstream). The log will also report if PTZ is supported by your camera.

## Channels

See PaperUI for a full list of channels and the descriptions. Each camera brand will have different channels depending on how much of the support for an API has been added. The channels are kept consistant as much as possible from brand to brand when possible to make upgrading to a different branded camera easier without the need to edit your rules as much.

## Full Example

Use the following examples to base your setup on to save some time. NOTE: If your cameras is secured with a user and password the links will not work and you will have to use the IMAGE channel to see a picture. FOSCAM cameras are the exception to this as they use the user and pass in plain text in the URL.

*.sitemap

Slider item=ipcamera_AMCREST_001_pan

Slider item=ipcamera_AMCREST_001_tilt

Slider item=ipcamera_AMCREST_001_zoom

Switch item=ipcamera_AMCREST_001_enableMotionAlarm

Switch item=ipcamera_AMCREST_001_motionAlarm

Switch item=ipcamera_AMCREST_001_audioAlarm

Image url="http://google.com/leaveLinkAsThis" item=ipcamera_AMCREST_001_image refresh=5000
             
                

*.things

Thing ipcamera:AMCREST:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_MEDIA_PROFILE=0]

## Roadmap for further development

If you need a feature added that is in an API it is very easy to add most of them, so raise a ticket with the request if you are not able to copy what I have already done and create a push request.
