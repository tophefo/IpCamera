# <bindingName> Binding

This binding allows you to use an ONVIF camera in Openhab2 directly.

## Supported Things

ONVIF thing type allows any compatible ONVIF camera to connect to openhab. This should allow any brand of compatible camera to be used.

## Discovery

Auto discovery is not supported currently. Manually add the ONVIF camera either via PaperUI or textual configuration which is covered below in more detail. Once the camera is added you then need to supply the IP address, and optionally a username and password if the camera is secured with these. Clicking on the pencil icon in PaperUI is how you reach these parameters.

## Binding Configuration

The binding can be configured with PaperUI by clicking on the pencil icon of any of the ONVIF cameras that you have manually added. 

It can also be manually configured with text files by doing the following. DO NOT try and change a setting using PaperUI after using textual configuration as the two will conflict as the text file locks the settings preventing them from changing.

The parameters for an ONVIF thing type are:

IPADDRESS
USERNAME
PASSWORD
ONVIF_PROFILE_NUMBER

Create a file called 'ipcamera.things' and save it to your things folder.

Thing ipcamera:ONVIF:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_PROFILE_NUMBER=0]




## Thing Configuration

After setting up the camera as per above, you will need to watch the log files where when the camera connects as it will list the supported profiles and what each profile will give you. My Amcrest camera needed to have an extra profile (substream) turned on and set to MJPG as this was not possible on the default profile 0 (Mainstream). The log will also report if PTZ is supported by your camera.

## Channels

See PaperUI for a full list of channels and the descriptions.
Currently there are two channels to ignore and they are the Image channel which requires the HTTP code to be finished first and a test button which I use to trigger new features that I am writing.

## Full Example

Use the following examples to base your setup on to save some time. It should be possible to fetch the link from the camera and to auto insert it into the Webview url which is why the binding provides a String channel that contains the link.

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
Thing ipcamera:ONVIF:001 [ IPADDRESS="192.168.1.2", PASSWORD="suitcase123456", USERNAME="Admin", ONVIF_PROFILE_NUMBER=0]

## Roadmap for further development

Next features being worked on currently is supporting HTTP API features from cameras that have the ability to access more advanced feature via an API. This requires some frame work to be created that allows basic and also digest authorization to be created first then the advanced features can be worked on. Motion and Audio alarms are high on the list to get supported.
