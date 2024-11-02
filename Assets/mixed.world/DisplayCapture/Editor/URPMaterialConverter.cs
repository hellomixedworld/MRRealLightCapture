using UnityEngine;
using UnityEditor;
using UnityEngine.SceneManagement;

namespace mixed.world.editor.tools
{
    public class URPMaterialConverter : Editor
    {
        private const string DEFAULT_MATERIAL_NAME = "Default-Material";
        private const string URP_DEFAULT_MATERIAL_PATH = "Packages/com.unity.render-pipelines.universal/Runtime/Materials/Lit.mat";

        [MenuItem("mixed.world/Convert Default-Materials to URP")]
        private static void ConvertDefaultMaterialsToURP()
        {
            Material urpDefaultMaterial = AssetDatabase.LoadAssetAtPath<Material>(URP_DEFAULT_MATERIAL_PATH);
            
            if (urpDefaultMaterial == null)
            {
                Debug.LogError("Could not find URP default material!");
                return;
            }

            // Start Undo group
            Undo.SetCurrentGroupName("Convert Default Materials to URP");
            int undoGroup = Undo.GetCurrentGroup();

            int replacedCount = 0;
            GameObject[] rootObjects = SceneManager.GetActiveScene().GetRootGameObjects();
            
            foreach (GameObject rootObject in rootObjects)
            {
                MeshRenderer[] renderers = rootObject.GetComponentsInChildren<MeshRenderer>(true);
                foreach (MeshRenderer renderer in renderers)
                {
                    Material[] materials = renderer.sharedMaterials;
                    bool materialChanged = false;
                    
                    // Record the renderer for Undo before making changes
                    Undo.RecordObject(renderer, "Convert Default Materials to URP");
                    
                    for (int i = 0; i < materials.Length; i++)
                    {
                        if (materials[i] != null && materials[i].name == DEFAULT_MATERIAL_NAME)
                        {
                            materials[i] = urpDefaultMaterial;
                            materialChanged = true;
                            replacedCount++;
                        }
                    }
                    
                    if (materialChanged)
                    {
                        renderer.sharedMaterials = materials;
                        EditorUtility.SetDirty(renderer);
                    }
                }
            }

            // Collapse all the recorded operations into a single group
            Undo.CollapseUndoOperations(undoGroup);

            if (replacedCount > 0)
            {
                Debug.Log($"Successfully replaced {replacedCount} Default-Material(s) with URP Default Material");
            }
            else
            {
                Debug.Log("No Default-Materials found in the current scene");
            }
        }
    }
}