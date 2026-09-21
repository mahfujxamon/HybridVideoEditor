import { Tabs } from 'expo-router';
import { Colors } from '../constants/theme';
import { StatusBar } from 'expo-status-bar';

export default function Layout() {
  return (
    <>
      <StatusBar style="light" />
      <Tabs screenOptions={{
        headerShown: false,
        tabBarStyle: { backgroundColor: Colors.surface, borderTopColor: Colors.border },
        tabBarActiveTintColor: Colors.primary,
        tabBarInactiveTintColor: Colors.textSecondary
      }}>
        <Tabs.Screen name="index" options={{ title: 'Editor' }} />
        <Tabs.Screen name="library" options={{ title: 'Library' }} />
      </Tabs>
    </>
  );
}