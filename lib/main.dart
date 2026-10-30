import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'core/config.dart';
import 'core/theme.dart';
import 'screens/shell.dart';
import 'services/auth.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: Colors.black,
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  await Supabase.initialize(url: AppConfig.supabaseUrl, anonKey: AppConfig.supabaseKey);
  Auth.instance.init(); // the profile loads in the background; the app opens right away
  runApp(const AniBiarApp());
}

class AniBiarApp extends StatelessWidget {
  const AniBiarApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: AppConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: buildTheme(),
      darkTheme: buildTheme(),
      themeMode: ThemeMode.dark,
      // keep layouts intact when the phone's font size is very large or very small
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: MediaQuery.of(context).textScaler.clamp(minScaleFactor: 0.9, maxScaleFactor: 1.1)),
        child: child!,
      ),
      home: const Shell(),
    );
  }
}
