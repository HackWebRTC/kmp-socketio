import SwiftUI
import shared

struct ContentView: View {
    init() {
        Greeting().greet()
    }

	var body: some View {
        VStack {
            Text("hello world")
        }
	}
}

struct ContentView_Previews: PreviewProvider {
	static var previews: some View {
		ContentView()
	}
}
