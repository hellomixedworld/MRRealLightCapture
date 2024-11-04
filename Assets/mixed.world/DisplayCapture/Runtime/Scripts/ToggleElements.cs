using System.Collections.Generic;
using UnityEngine;

namespace mixed.world.meta.displaycapture.utils
{
    public class ToggleElements : MonoBehaviour
    {
        [SerializeField]
        private List<GameObject> toggleableObjects = new List<GameObject>();

        public void Toggle()
        {
            foreach (GameObject obj in toggleableObjects)
            {
                if (obj != null)
                {
                    obj.SetActive(!obj.activeSelf);
                }
            }
        }

        public void Toggle(bool on)
        {
            foreach (GameObject obj in toggleableObjects)
            {
                if (obj != null)
                {
                    obj.SetActive(on);
                }
            }
        }
    }
} 