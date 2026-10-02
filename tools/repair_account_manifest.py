from pathlib import Path
import xml.etree.ElementTree as ET


ANDROID = 'http://schemas.android.com/apk/res/android'
ACCOUNT = 'com.nenotv.player.AccountActivity'


def repair_manifest(path):
    path = Path(path)
    tree = ET.parse(path)
    application = tree.getroot().find('application')
    if application is None:
        raise ValueError('Android manifest has no application element')
    name = '{' + ANDROID + '}name'
    matches = [activity for activity in application.findall('activity')
               if activity.get(name) in (ACCOUNT, '.AccountActivity', 'AccountActivity')]
    if len(matches) > 1:
        raise ValueError('Duplicate AccountActivity registrations')
    activity = matches[0] if matches else ET.SubElement(application, 'activity')
    activity.set(name, ACCOUNT)
    activity.set('{' + ANDROID + '}exported', 'false')
    ET.register_namespace('android', ANDROID)
    tree.write(path, encoding='utf-8', xml_declaration=True)
