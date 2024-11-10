using UnityEngine;
using UnityEditor;
using System.IO;
using System.Xml;
using System.Text;

namespace mixed.world.meta.displaycapture
{
    public class ManifestUpdater : Editor
    {
        private const string MANIFEST_PATH = "Assets/Plugins/Android/AndroidManifest.xml";

        [MenuItem("mixed.world/DisplayCapture/Update AndroidManifest.xml")]
        public static void UpdateManifest()
        {
            if (!File.Exists(MANIFEST_PATH))
            {
                EditorUtility.DisplayDialog("Error", 
                    "Manifest does not exist. Please call Meta/Tools/Create store-compatible AndroidManifest.xml first!", 
                    "OK");
                return;
            }

            try
            {
                string originalContent = File.ReadAllText(MANIFEST_PATH);

                XmlDocument doc = new XmlDocument();
                doc.Load(MANIFEST_PATH);

                XmlElement manifestElement = doc.DocumentElement;
                if (!manifestElement.HasAttribute("xmlns:tools"))
                {
                    manifestElement.SetAttribute("xmlns:tools", "http://schemas.android.com/tools");
                }

                XmlNode manifestNode = doc.SelectSingleNode("manifest");
                XmlNode applicationNode = doc.SelectSingleNode("manifest/application");

                if (manifestNode == null || applicationNode == null)
                {
                    Debug.LogError("Invalid AndroidManifest.xml structure");
                    return;
                }

                bool manifestUpdated = false;
                bool applicationUpdated = false;

                if (!ManifestContainsPermission(manifestNode, "android.permission.FOREGROUND_SERVICE") ||
                    !ManifestContainsPermission(manifestNode, "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION") ||
                    !ManifestContainsPermissionWithRemoval(manifestNode, "android.permission.READ_PHONE_STATE") ||
                    !ManifestContainsPermission(manifestNode, "android.permission.WRITE_EXTERNAL_STORAGE"))
                {
                    AddPermissionIfMissing(doc, manifestNode, "android.permission.FOREGROUND_SERVICE");
                    AddPermissionIfMissing(doc, manifestNode, "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION");
                    AddPermissionWithRemovalIfMissing(doc, manifestNode, "android.permission.READ_PHONE_STATE");
                    AddPermissionIfMissing(doc, manifestNode, "android.permission.WRITE_EXTERNAL_STORAGE");
                    manifestUpdated = true;
                }

                if (!ApplicationContainsComponent(applicationNode, "activity", "com.trev3d.DisplayCapture.DisplayCaptureRequestActivity") ||
                    !ApplicationContainsComponent(applicationNode, "service", "com.trev3d.DisplayCapture.DisplayCaptureNotificationService"))
                {
                    AddActivityIfMissing(doc, applicationNode);
                    AddServiceIfMissing(doc, applicationNode);
                    applicationUpdated = true;
                }

                if (manifestUpdated || applicationUpdated)
                {
                    Undo.RegisterCompleteObjectUndo(
                        AssetDatabase.LoadAssetAtPath<TextAsset>(MANIFEST_PATH),
                        "Update AndroidManifest.xml"
                    );

                    using (XmlTextWriter writer = new XmlTextWriter(MANIFEST_PATH, new UTF8Encoding(false)))
                    {
                        writer.Formatting = Formatting.Indented;
                        doc.Save(writer);
                    }

                    Undo.RegisterCompleteObjectUndo(new ManifestUndoHelper(MANIFEST_PATH, originalContent), 
                        "Update AndroidManifest.xml");

                    Debug.Log("AndroidManifest.xml has been updated successfully!");
                    AssetDatabase.Refresh();
                }
                else
                {
                    Debug.Log("AndroidManifest.xml already contains all required entries.");
                }
            }
            catch (System.Exception e)
            {
                Debug.LogError($"Error updating AndroidManifest.xml: {e.Message}");
            }
        }

        private static bool ManifestContainsPermission(XmlNode manifestNode, string permission)
        {
            XmlNodeList permissions = manifestNode.SelectNodes("uses-permission");
            foreach (XmlNode node in permissions)
            {
                if (node.Attributes?["android:name"]?.Value == permission)
                    return true;
            }
            return false;
        }

        private static bool ManifestContainsPermissionWithRemoval(XmlNode manifestNode, string permission)
        {
            XmlNodeList permissions = manifestNode.SelectNodes("uses-permission");
            foreach (XmlNode node in permissions)
            {
                if (node.Attributes?["android:name"]?.Value == permission && 
                    node.Attributes?["tools:node"]?.Value == "remove")
                    return true;
            }
            return false;
        }

        private static void AddPermissionIfMissing(XmlDocument doc, XmlNode manifestNode, string permission)
        {
            if (!ManifestContainsPermission(manifestNode, permission))
            {
                XmlElement permissionElement = doc.CreateElement("uses-permission");
                permissionElement.SetAttribute("name", "http://schemas.android.com/apk/res/android", permission);
                manifestNode.AppendChild(permissionElement);
            }
        }

        private static void AddPermissionWithRemovalIfMissing(XmlDocument doc, XmlNode manifestNode, string permission)
        {
            if (!ManifestContainsPermissionWithRemoval(manifestNode, permission))
            {
                XmlElement permissionElement = doc.CreateElement("uses-permission");
                permissionElement.SetAttribute("name", "http://schemas.android.com/apk/res/android", permission);
                permissionElement.SetAttribute("node", "http://schemas.android.com/tools", "remove");
                manifestNode.AppendChild(permissionElement);
            }
        }

        private static bool ApplicationContainsComponent(XmlNode applicationNode, string componentType, string componentName)
        {
            XmlNodeList components = applicationNode.SelectNodes(componentType);
            foreach (XmlNode node in components)
            {
                if (node.Attributes?["android:name"]?.Value == componentName)
                    return true;
            }
            return false;
        }

        private static void AddActivityIfMissing(XmlDocument doc, XmlNode applicationNode)
        {
            if (!ApplicationContainsComponent(applicationNode, "activity", "com.trev3d.DisplayCapture.DisplayCaptureRequestActivity"))
            {
                XmlElement activityElement = doc.CreateElement("activity");
                activityElement.SetAttribute("name", "http://schemas.android.com/apk/res/android", "com.trev3d.DisplayCapture.DisplayCaptureRequestActivity");
                activityElement.SetAttribute("exported", "http://schemas.android.com/apk/res/android", "false");
                applicationNode.AppendChild(activityElement);
            }
        }

        private static void AddServiceIfMissing(XmlDocument doc, XmlNode applicationNode)
        {
            if (!ApplicationContainsComponent(applicationNode, "service", "com.trev3d.DisplayCapture.DisplayCaptureNotificationService"))
            {
                XmlElement serviceElement = doc.CreateElement("service");
                serviceElement.SetAttribute("name", "http://schemas.android.com/apk/res/android", "com.trev3d.DisplayCapture.DisplayCaptureNotificationService");
                serviceElement.SetAttribute("exported", "http://schemas.android.com/apk/res/android", "false");
                serviceElement.SetAttribute("foregroundServiceType", "http://schemas.android.com/apk/res/android", "mediaProjection");
                applicationNode.AppendChild(serviceElement);
            }
        }

        private class ManifestUndoHelper : ScriptableObject
        {
            private string filePath;
            private string originalContent;

            public ManifestUndoHelper(string path, string content)
            {
                filePath = path;
                originalContent = content;
            }

            private void OnDestroy()
            {
                if (File.Exists(filePath))
                {
                    File.WriteAllText(filePath, originalContent);
                    AssetDatabase.Refresh();
                }
            }
        }
    }
} 