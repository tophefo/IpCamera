# <bindingName> Binding

This binding allows you to use IP cameras in Openhab2 directly.

## Supported Things

ONVIF: Use for all ONVIF Cameras from any brand that do not have an API.
AMCREST: Use for all current Amcrest Cameras as they support an API as well as ONVIF.
FOSCAM: Use for all current FOSCAM Cameras as they support an API as well as ONVIF.
AXIS: Use for all current Axis Cameras as they support an API as well as ONVIF

## Discovery

Auto discovery is not supported currently. Manually add the ONVIF camera either via PaperUI or textual configuration which is covered below in more detail. Once the camera is added you then need to supply the IP address and port that ONVIF uses. Optionally a username and password can also be filled in if the camera is secured with these. Clicking on the pencil icon in PaperUI is how you reach these parameters.

## Binding Configuration

The binding can be configured with PaperUI by clicking on the pencil icon of any of the cameras that you have manually added. 

It can also be manually configured with text files by doing the following. DO NOT try and change a setting using PaperUI after using textual configuration as the two will conflict as the text file locks the settings preventing them from changing.

The parameters that can be used are:

IPADDRESS

ONVIF_PORT

USERNAME

PASSWORD

ONVIF_MEDIA_PROFILE

CHECK_STATUS_DELAY


Create a file called 'ipcamera.things' and save it to your things folder.

Thing ipcamera:ONVIF:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_MEDIA_PROFILE=0]




## Thing Configuration

After setting up the camera as per above, you will need to watch the log files where when the camera connects as it will list the supported profiles and what each profile will give you. My Amcrest camera needed to have an extra profile (substream) turned on and set to MJPG as this was not possible on the default profile 0 (Mainstream). The log will also report if PTZ is supported by your camera.

## Channels

See PaperUI for a full list of channels and the descriptions.
Currently there are two channels to ignore and they are the Image channel which requires the HTTP code to be finished first and a test button which I use to trigger new features that I am writing.

## Full Example

Use the following examples to base your setup on to save some time. It should be possible to fetch the link from the camera and to auto insert it into the Webview url which is why the binding provides a String channel that contains the link. NOTE: If your cameras is secured with a user and password the links will not work and will require a future feature to be added to the binding.

*.sitemap

Slider item=Cam001Pan

Slider item=Cam001Tilt

Slider item=Cam001Zoom

Webview url="http://192.168.1.2/onvifsnapshot/media_service/snapshot?channel=1&subtype=0" height=30
              
                
 *.items   
             
Dimmer Cam001Pan {channel="ipcamera:ONVIF:001:pan"}

Dimmer Cam001Tilt {channel="ipcamera:ONVIF:001:tilt"}

Dimmer Cam001Zoom {channel="ipcamera:ONVIF:001:zoom"}


*.things

Thing ipcamera:ONVIF:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_MEDIA_PROFILE=0]

## Roadmap for further development

Audio Alarm support for Amcrest cameras is being looked at, followed by a way to give a picture in Openhab from cameras with password protected features. If you need a feature added that is in an API it is very easy to add most of them, so raise a ticket with the request.
